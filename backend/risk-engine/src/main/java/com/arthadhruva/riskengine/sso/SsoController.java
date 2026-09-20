package com.arthadhruva.riskengine.sso;

import com.arthadhruva.riskengine.security.JwtService;
import com.arthadhruva.riskengine.security.User;
import com.arthadhruva.riskengine.security.UserService;
import com.arthadhruva.riskengine.tenant.Organization;
import com.arthadhruva.riskengine.tenant.OrganizationService;
import com.arthadhruva.riskengine.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OIDC authorization-code login per organization. Flow: {@code GET /sso/{org}/login} redirects to the
 * IdP with a signed, short-lived {@code state} (org + nonce, so the callback needs no server-side
 * session); {@code GET /sso/callback} exchanges the code, validates the ID token (signature via the
 * IdP's JWKS, issuer, expiry, audience = our client id, and the nonce), maps it to an EXISTING
 * local account by email, and issues the same session JWT a password login would. No auto-provisioning:
 * an unknown identity is refused, so being in the IdP is not enough to get an account. The IdP's own
 * MFA replaces the local TOTP step; local password login stays available as the ADMIN break-glass path.
 */
@RestController
public class SsoController {

    private static final Logger log = LoggerFactory.getLogger(SsoController.class);
    private static final String STATE_PURPOSE = "sso-state";

    private final OrganizationService organizations;
    private final UserService users;
    private final SsoConfigService configs;
    private final JwtService jwt;
    private final String publicUrl;
    private final String frontendUrl;
    private final RestClient http = RestClient.create();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, JsonNode> discoveryCache = new ConcurrentHashMap<>();

    public SsoController(OrganizationService organizations, UserService users, SsoConfigService configs, JwtService jwt,
                         @Value("${app.public-url:http://localhost:8080}") String publicUrl,
                         @Value("${app.frontend-url}") String frontendUrl) {
        this.organizations = organizations;
        this.users = users;
        this.configs = configs;
        this.jwt = jwt;
        this.publicUrl = publicUrl;
        this.frontendUrl = frontendUrl;
    }

    // ---- admin configuration ---------------------------------------------------------------

    public record ConfigRequest(String issuer, String clientId, String clientSecret, Boolean enabled, Boolean enforced) {
    }

    @GetMapping("/admin/sso")
    public Map<String, Object> getConfig() {
        return configs.find(TenantContext.get())
                .map(c -> Map.<String, Object>of("configured", true, "issuer", c.issuer(), "clientId", c.clientId(),
                        "enabled", c.enabled(), "enforced", c.enforced()))
                .orElse(Map.of("configured", false));
    }

    @PutMapping("/admin/sso")
    public ResponseEntity<?> putConfig(@RequestBody ConfigRequest r) {
        if (r.issuer() == null || !r.issuer().startsWith("http") || r.clientId() == null || r.clientId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "issuer (http/https URL) and clientId are required"));
        }
        Long tenant = TenantContext.get();
        var existing = configs.find(tenant);
        String secret = r.clientSecret() != null && !r.clientSecret().isBlank() ? r.clientSecret()
                : existing.map(SsoConfigService.SsoConfig::clientSecret).orElse(null);
        if (secret == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "clientSecret is required"));
        }
        configs.save(tenant, new SsoConfigService.SsoConfig(r.issuer(), r.clientId(), secret,
                r.enabled() == null || r.enabled(), r.enforced() != null && r.enforced()));
        return ResponseEntity.ok(Map.of("saved", true));
    }

    // ---- login flow (public) ---------------------------------------------------------------

    @GetMapping("/sso/{orgSlug}/login")
    public ResponseEntity<?> start(@PathVariable String orgSlug) {
        Organization org = organizations.resolveActiveBySlug(orgSlug).orElse(null);
        if (org == null) {
            return failure("sso_unavailable");
        }
        TenantContext.set(org.getId());
        try {
            var config = configs.find(org.getId()).filter(SsoConfigService.SsoConfig::enabled).orElse(null);
            if (config == null) {
                return failure("sso_unavailable");
            }
            String nonce = UUID.randomUUID().toString();
            String state = jwt.issueClaimsToken(STATE_PURPOSE, Duration.ofMinutes(10), Map.of("org", org.getId(), "nonce", nonce));
            String authEndpoint = discover(config.issuer()).get("authorization_endpoint").asString();
            URI redirect = UriComponentsBuilder.fromUriString(authEndpoint)
                    .queryParam("response_type", "code").queryParam("client_id", config.clientId())
                    .queryParam("redirect_uri", publicUrl + "/v1/sso/callback").queryParam("scope", "openid email profile")
                    .queryParam("state", state).queryParam("nonce", nonce).build().encode().toUri();
            return ResponseEntity.status(HttpStatus.FOUND).location(redirect).build();
        } catch (Exception e) {
            log.warn("SSO start failed for {}: {}", orgSlug, e.toString());
            return failure("sso_unavailable");
        } finally {
            TenantContext.clear();
        }
    }

    @GetMapping("/sso/callback")
    public ResponseEntity<?> callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state) {
        var claims = state == null ? null : jwt.parseClaimsToken(state, STATE_PURPOSE).orElse(null);
        if (code == null || claims == null) {
            return failure("sso_failed");
        }
        Long orgId = claims.get("org", Long.class);
        String nonce = claims.get("nonce", String.class);
        TenantContext.set(orgId);
        try {
            var config = configs.find(orgId).filter(SsoConfigService.SsoConfig::enabled).orElse(null);
            if (config == null) {
                return failure("sso_failed");
            }
            JsonNode meta = discover(config.issuer());
            var form = new LinkedMultiValueMap<String, String>();
            form.add("grant_type", "authorization_code");
            form.add("code", code);
            form.add("redirect_uri", publicUrl + "/v1/sso/callback");
            form.add("client_id", config.clientId());
            form.add("client_secret", config.clientSecret());
            String tokenBody = http.post().uri(meta.get("token_endpoint").asString())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(String.class);
            String idToken = mapper.readTree(tokenBody).get("id_token").asString();

            NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(meta.get("jwks_uri").asString()).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(config.issuer()));
            Jwt id = decoder.decode(idToken);
            if (id.getAudience() == null || !id.getAudience().contains(config.clientId())
                    || !nonce.equals(id.getClaimAsString("nonce"))) {
                return failure("sso_failed");
            }
            String email = id.getClaimAsString("email");
            if (email == null || email.isBlank()) {
                return failure("sso_failed");
            }
            User user = users.findByOrganizationAndEmail(orgId, email).orElse(null);
            if (user == null || !user.isEnabled() || !user.isActivated() || user.isCurrentlyLocked()) {
                return failure("no_account");
            }
            JwtService.IssuedToken session = jwt.issue(user.getUsername(), user.getRole(), orgId);
            String fragment = "token=" + session.token() + "&username=" + user.getUsername() + "&role=" + user.getRole().name()
                    + "&sandbox=" + user.getOrganization().isSandbox();
            return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, frontendUrl + "/sso-complete#" + fragment).build();
        } catch (Exception e) {
            log.warn("SSO callback failed: {}", e.toString());
            return failure("sso_failed");
        } finally {
            TenantContext.clear();
        }
    }

    private ResponseEntity<?> failure(String code) {
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, frontendUrl + "/login?ssoError=" + code).build();
    }

    private JsonNode discover(String issuer) {
        return discoveryCache.computeIfAbsent(issuer, i -> {
            String url = i.endsWith("/") ? i + ".well-known/openid-configuration" : i + "/.well-known/openid-configuration";
            return mapper.readTree(http.get().uri(url).retrieve().body(String.class));
        });
    }
}
