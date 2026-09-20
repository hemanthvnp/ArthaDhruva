package com.arthadhruva.riskengine.webhook;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * SSRF guard for tenant-supplied webhook URLs: the server will call whatever URL an admin
 * registers, so without this an admin could aim it at the metadata service, Postgres, or any other
 * internal address. Checked at registration AND again at delivery (DNS can change between the two,
 * "DNS rebinding"), and redirects are never followed by the worker.
 */
@Component
public class UrlGuard {

    private final boolean allowPrivate;

    public UrlGuard(@Value("${webhook.allow-private-targets:false}") boolean allowPrivate) {
        this.allowPrivate = allowPrivate;
    }

    /** @throws IllegalArgumentException with a reason safe to show the admin */
    public URI check(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a valid URL");
        }
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("URL must be http or https");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("URL must have a host and no embedded credentials");
        }
        if (!allowPrivate) {
            try {
                for (InetAddress a : InetAddress.getAllByName(uri.getHost())) {
                    if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isSiteLocalAddress()
                            || a.isLinkLocalAddress() || a.isMulticastAddress()) {
                        throw new IllegalArgumentException("URL resolves to a private or loopback address");
                    }
                }
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Host does not resolve");
            }
        }
        return uri;
    }
}
