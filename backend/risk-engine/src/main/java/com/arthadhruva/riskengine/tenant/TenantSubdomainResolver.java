package com.arthadhruva.riskengine.tenant;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves the tenant from the request host: with {@code app.base-domain=arthadhruva.app},
 * {@code acme.arthadhruva.app} means organization {@code acme}. Additive to the older explicit
 * {@code orgSlug} login field: both work during a deprecation window ({@code
 * auth.org-slug-login-enabled}, default true), a request carrying both must agree, and the
 * legacy path logs a warning on use and at startup so its continued use is visible everywhere,
 * not just to someone reading the code. Wildcard DNS and a wildcard certificate for the base
 * domain are what make this work in a real deployment (nginx already forwards the original Host).
 */
@Component
public class TenantSubdomainResolver {

    private static final Logger log = LoggerFactory.getLogger(TenantSubdomainResolver.class);

    private final String baseDomain;
    private final boolean orgSlugLoginEnabled;

    public TenantSubdomainResolver(@Value("${app.base-domain:}") String baseDomain,
                                   @Value("${auth.org-slug-login-enabled:true}") boolean orgSlugLoginEnabled) {
        this.baseDomain = baseDomain == null ? "" : baseDomain.trim().toLowerCase();
        this.orgSlugLoginEnabled = orgSlugLoginEnabled;
    }

    @PostConstruct
    void announce() {
        if (orgSlugLoginEnabled) {
            log.warn("DEPRECATED: login by orgSlug field is still enabled (auth.org-slug-login-enabled=true). "
                    + "Subdomain login {} is the supported path; disable the field once clients have moved.",
                    baseDomain.isEmpty() ? "(app.base-domain not set)" : "*." + baseDomain);
        }
    }

    public boolean orgSlugLoginEnabled() {
        return orgSlugLoginEnabled;
    }

    /** The organization slug encoded in the host, if the host is a single-label subdomain of the base domain. */
    public Optional<String> slugFromHost(String hostHeader) {
        if (baseDomain.isEmpty() || hostHeader == null) {
            return Optional.empty();
        }
        String host = hostHeader.toLowerCase();
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        String suffix = "." + baseDomain;
        if (!host.endsWith(suffix)) {
            return Optional.empty();
        }
        String label = host.substring(0, host.length() - suffix.length());
        return label.matches("[a-z0-9][a-z0-9-]{0,62}") ? Optional.of(label) : Optional.empty();
    }
}
