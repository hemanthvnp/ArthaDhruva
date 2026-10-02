package com.arthadhruva.riskengine.webhook;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlGuardTest {

    private static boolean isPublic(String literal) throws Exception {
        return UrlGuard.isPublic(InetAddress.getByName(literal));   // a literal: no DNS lookup
    }

    @Test
    void internalAndReservedAddressesAreNotPublic() throws Exception {
        for (String address : List.of(
                "127.0.0.1", "0.0.0.0", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1",
                "169.254.169.254",           // cloud metadata service
                "100.64.0.1",                // carrier-grade NAT
                "192.0.0.8", "198.18.0.1", "224.0.0.1", "255.255.255.255",
                "::1", "::", "fe80::1", "fc00::1", "fd12:3456::1", "ff02::1")) {
            assertFalse(isPublic(address), address);
        }
    }

    /** An IPv6 address that merely wraps an internal IPv4 one must not slip through. */
    @Test
    void ipv4AddressesHiddenInsideIpv6AreUnwrapped() throws Exception {
        for (String address : List.of(
                "::ffff:127.0.0.1", "::ffff:169.254.169.254", "::ffff:10.0.0.1",   // IPv4-mapped
                "64:ff9b::7f00:1", "64:ff9b::a00:1",                                // NAT64 of 127.0.0.1 / 10.0.0.1
                "2002:7f00:0001::", "2002:c0a8:0101::",                             // 6to4 of 127.0.0.1 / 192.168.1.1
                "2001:0:4136:e378:8000:63bf:f5ff:fffe")) {                          // Teredo of 10.0.0.1 (stored inverted)
            assertFalse(isPublic(address), address);
        }
        // the same wrappers around a public address stay public
        assertTrue(isPublic("64:ff9b::808:808"));                                   // NAT64 of 8.8.8.8
        assertTrue(isPublic("2002:0808:0808::"));                                   // 6to4 of 8.8.8.8
        assertTrue(isPublic("2001:0:4136:e378:8000:63bf:f7f7:f7f7"));               // Teredo of 8.8.8.8
    }

    @Test
    void ordinaryAddressesArePublic() throws Exception {
        for (String address : List.of("8.8.8.8", "1.1.1.1", "172.32.0.1", "100.128.0.1", "198.20.0.1",
                "2606:4700:4700::1111", "::ffff:8.8.8.8")) {
            assertTrue(isPublic(address), address);
        }
    }

    @Test
    void urlsMustBeHttpsWithoutCredentialsFragmentsOrInternalTargets() {
        UrlGuard guard = new UrlGuard(false);
        assertEquals("https://8.8.8.8/hook", guard.check(" https://8.8.8.8/hook ").toString());
        for (String bad : List.of("http://8.8.8.8/hook", "ftp://8.8.8.8/", "https://user:pw@8.8.8.8/", "https://8.8.8.8/#frag",
                "https://127.0.0.1/", "https://169.254.169.254/latest/meta-data", "https://[::1]/", "https://10.0.0.5:8443/x",
                "not a url", "https:///nohost")) {
            assertThrows(IllegalArgumentException.class, () -> guard.check(bad), bad);
        }
    }

    @Test
    void localDevelopmentModeAllowsPrivateHttpTargets() throws Exception {
        UrlGuard guard = new UrlGuard(true);
        assertEquals("http://127.0.0.1:9000/hook", guard.check("http://127.0.0.1:9000/hook").toString());
        assertTrue(guard.isAllowedTarget(InetAddress.getByName("10.0.0.1")));
        assertFalse(new UrlGuard(false).isAllowedTarget(InetAddress.getByName("10.0.0.1")));
    }
}
