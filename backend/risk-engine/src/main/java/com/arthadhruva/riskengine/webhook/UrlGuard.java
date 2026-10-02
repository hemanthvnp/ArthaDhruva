package com.arthadhruva.riskengine.webhook;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * SSRF policy for every server-side request to a URL a tenant supplied (webhooks, OIDC issuers).
 *
 * <p>{@link #check} validates a URL when it is registered, for a clear error message. The binding
 * control is {@link #isPublic}, applied by {@link OutboundHttp}'s DNS resolver to the exact addresses
 * each connection is made to: checking a hostname at registration and connecting later lets a
 * DNS-rebinding attacker answer "public" to the check and "169.254.169.254" to the connection.
 *
 * <p>Blocked: loopback, unspecified, private (RFC 1918), carrier-grade NAT (100.64/10), link-local
 * (including cloud metadata 169.254.169.254), multicast, benchmarking (198.18/15), IETF protocol
 * assignments (192.0.0/24), reserved (240/4), broadcast, IPv6 unique-local (fc00::/7), site-local, and
 * IPv4 addresses embedded in IPv6 (mapped, NAT64, 6to4, Teredo), which are checked as IPv4.
 */
@Component
public class UrlGuard {

    private final boolean allowPrivate;

    public UrlGuard(@Value("${webhook.allow-private-targets:false}") boolean allowPrivate) {
        this.allowPrivate = allowPrivate;
    }

    public boolean allowsPrivateTargets() {
        return allowPrivate;
    }

    /** @throws IllegalArgumentException with a reason safe to show the admin */
    public URI check(String url) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Not a valid URL");
        }
        String scheme = uri.getScheme();
        boolean https = "https".equalsIgnoreCase(scheme);
        if (!https && !("http".equalsIgnoreCase(scheme) && allowPrivate)) {
            // Payloads carry loan data and OIDC exchanges carry client secrets: TLS is mandatory outside
            // local development.
            throw new IllegalArgumentException("URL must use https");
        }
        if (uri.getHost() == null || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("URL must have a host and no embedded credentials");
        }
        if (uri.getFragment() != null) {
            throw new IllegalArgumentException("URL must not contain a fragment");
        }
        if (!allowPrivate) {
            try {
                for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                    if (!isPublic(address)) {
                        throw new IllegalArgumentException("URL resolves to a private, loopback or reserved address");
                    }
                }
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Host does not resolve");
            }
        }
        return uri;
    }

    /** Whether a connection to this address is allowed (always true when private targets are enabled). */
    public boolean isAllowedTarget(InetAddress address) {
        return allowPrivate || isPublic(address);
    }

    static boolean isPublic(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            return isPublicIpv4(b);
        }
        if (address instanceof Inet6Address) {
            int first = b[0] & 0xff;
            if ((first & 0xfe) == 0xfc) {                     // fc00::/7 unique local
                return false;
            }
            if (isZero(b, 0, 10) && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {   // ::ffff:a.b.c.d
                return isPublicIpv4(slice(b, 12));
            }
            if (isZero(b, 0, 12)) {                           // ::a.b.c.d (deprecated compatible form)
                return isPublicIpv4(slice(b, 12));
            }
            if ((b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b) {
                return isPublicIpv4(slice(b, 12));            // 64:ff9b::/96 NAT64
            }
            if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x02) {
                return isPublicIpv4(new byte[]{b[2], b[3], b[4], b[5]});   // 2002::/16 6to4
            }
            if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x01 && b[2] == 0 && b[3] == 0) {
                // 2001::/32 Teredo: the client address is the last 32 bits, inverted
                return isPublicIpv4(new byte[]{(byte) ~b[12], (byte) ~b[13], (byte) ~b[14], (byte) ~b[15]});
            }
            return true;
        }
        return false;
    }

    private static boolean isPublicIpv4(byte[] b) {
        int a = b[0] & 0xff;
        int c = b[1] & 0xff;
        if (a == 0 || a == 10 || a == 127 || a >= 224) {      // this-network, private, loopback, multicast/reserved/broadcast
            return false;
        }
        if (a == 100 && c >= 64 && c <= 127) {                 // 100.64.0.0/10 carrier-grade NAT
            return false;
        }
        if (a == 169 && c == 254) {                             // link-local incl. cloud metadata
            return false;
        }
        if (a == 172 && c >= 16 && c <= 31) {                   // 172.16.0.0/12
            return false;
        }
        if (a == 192 && c == 168) {                              // 192.168.0.0/16
            return false;
        }
        if (a == 192 && c == 0 && (b[2] & 0xff) == 0) {          // 192.0.0.0/24 IETF protocol assignments
            return false;
        }
        return !(a == 198 && (c == 18 || c == 19));             // 198.18.0.0/15 benchmarking
    }

    private static boolean isZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static byte[] slice(byte[] b, int from) {
        return new byte[]{b[from], b[from + 1], b[from + 2], b[from + 3]};
    }
}
