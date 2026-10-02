package com.arthadhruva.riskengine.sso;

import com.arthadhruva.riskengine.audit.NotAudited;
import com.arthadhruva.riskengine.security.AuthController;
import com.arthadhruva.riskengine.security.AuthThrottle;
import com.arthadhruva.riskengine.security.JwtService;
import com.arthadhruva.riskengine.security.User;
import com.arthadhruva.riskengine.security.UserService;
import com.arthadhruva.riskengine.tenant.Organization;
import com.arthadhruva.riskengine.tenant.OrganizationService;
import com.arthadhruva.riskengine.tenant.TenantContext;
import com.arthadhruva.riskengine.webhook.OutboundHttp;
import com.arthadhruva.riskengine.webhook.UrlGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OIDC authorization-code login per organization, hardened along the lines of the OAuth 2.0 Security
 * Best Current Practice (RFC 9700):
 * <ul>
 *   <li><b>Browser binding + PKCE.</b> {@code /login} sets an HttpOnly cookie holding a random value;
 *       the signed {@code state} carries its hash and the PKCE verifier is derived from it. A callback URL
 *       replayed into another browser (login CSRF) fails, and an intercepted authorization code is useless
 *       without the verifier.</li>
 *   <li><b>SSRF.</b> Discovery, token and JWKS requests go through {@link OutboundHttp} (https only,
 *       public addresses only, checked at connect time); discovery is cached for an hour.</li>
 *   <li><b>Identity.</b> Accounts are linked by the IdP's stable (issuer, subject). The first SSO sign-in
 *       links an existing account by email, and only if the IdP does not say the email is unverified;
 *       once linked, the subject must match. No auto-provisioning: being in the IdP is not enough.</li>
 *   <li><b>No token in a URL.</b> The callback redirects with a single-use, 60-second hand-off code in
 *       the fragment; the frontend exchanges it for a session with a POST.</li>
 * </ul>
 * The IdP's own MFA replaces the local TOTP step; local password login stays the ADMIN break-glass path.
 */
@RestController
public class SsoController {

    private static final Logger log = LoggerFactory.getLogger(SsoController.class);
    private static final String STATE_PURPOSE = "sso-state";
    private static final String TXN_COOKIE = "sso_txn";
    private static final Duration FLOW_TTL = Duration.ofMinutes(10);
    private static final Duration HANDOFF_TTL = Duration.ofSeconds(60);
    private static final Duration DISCOVERY_TTL = Duration.ofHours(1);

    private final OrganizationService organizations;
    private final UserService users;
    private final SsoConfigService configs;
    private final JwtService jwt;
    private final UrlGuard urlGuard;
    private final OutboundHttp http;
    private final JdbcTemplate jdbc;
    private final AuthThrottle throttle;
    private final String publicUrl;
    private final String frontendUrl;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Cached<JsonNode>> discoveryCache = new ConcurrentHashMap<>();
    private final Map<String, Cached<NimbusJwtDecoder>> decoderCache = new ConcurrentHashMap<>();

    private record Cached<T>(T value, Instant expiresAt) {
    }

    public SsoController(OrganizationService organizations, UserService users, SsoConfigService configs, JwtService jwt,
                         UrlGuard urlGuard, OutboundHttp http, JdbcTemplate jdbc, AuthThrottle throttle,
                         @Value("${app.public-url:http://localhost:8080}") String publicUrl,
                         @Value("${app.frontend-url}") String frontendUrl) {
        this.organizations = organizations;
        this.users = users;
        this.configs = configs;
        this.jwt = jwt;
        this.urlGuard = urlGuard;
        this.http = http;
        this.jdbc = jdbc;
        this.throttle = throttle;
        this.publicUrl = publicUrl;
        this.frontendUrl = frontendUrl;
    }

    // ---- admin configuration ---------------------------------------------------------------

    public record ConfigRequest(@NotBlank @Size(max = 300) String issuer, @NotBlank @Size(max = 200) String clientId,
                                @Size(max = 300) String clientSecret, Boolean enabled, Boolean enforced) {
    }

    @GetMapping("/admin/sso")
    public Map<String, Object> getConfig() {
        return configs.find(TenantContext.get())
                .map(c -> Map.<String, Object>of("configured", true, "issuer", c.issuer(), "clientId", c.clientId(),
                        "enabled", c.enabled(), "enforced", c.enforced()))
                .orElse(Map.of("configured", false));
    }

    /** Validates the issuer before saving: https, publicly routable, and its discovery document must name
     * exactly this issuer (OIDC Discovery 1.0 section 4.3), which catches typos and look-alike issuers. */
    @PutMapping("/admin/sso")
    public ResponseEntity<?> putConfig(@Valid @RequestBody ConfigRequest r) {
        String issuer = r.issuer().trim();
        try {
            urlGuard.check(issuer);
            JsonNode meta = fetchDiscovery(issuer);
            if (!issuer.equals(text(meta, "issuer"))) {
                return ResponseEntity.badRequest().body(Map.of("error", "The provider's discovery document names a different issuer"));
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Could not read the provider's OpenID configuration"));
        }
        Long tenant = TenantContext.get();
        String secret = r.clientSecret() != null && !r.clientSecret().isBlank() ? r.clientSecret()
                : configs.find(tenant).map(SsoConfigService.SsoConfig::clientSecret).orElse(null);
        if (secret == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "clientSecret is required"));
        }
        configs.save(tenant, new SsoConfigService.SsoConfig(issuer, r.clientId().trim(), secret,
                r.enabled() == null || r.enabled(), r.enforced() != null && r.enforced()));
        return ResponseEntity.ok(Map.of("saved", true));
    }

    // ---- login flow (public) ---------------------------------------------------------------

    @NotAudited
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
            JsonNode meta = discovery(config.issuer());
            URI authEndpoint = urlGuard.check(text(meta, "authorization_endpoint"));
            String txn = randomToken();
            String nonce = UUID.randomUUID().toString();
            String state = jwt.issueClaimsToken(STATE_PURPOSE, FLOW_TTL,
                    Map.of("org", org.getId(), "nonce", nonce, "bh", sha256Hex(txn)));
            URI redirect = UriComponentsBuilder.fromUri(authEndpoint)
                    .queryParam("response_type", "code").queryParam("client_id", config.clientId())
                    .queryParam("redirect_uri", callbackUri()).queryParam("scope", "openid email profile")
                    .queryParam("state", state).queryParam("nonce", nonce)
                    .queryParam("code_challenge", base64Url(sha256(pkceVerifier(txn))))
                    .queryParam("code_challenge_method", "S256")
                    .build().encode().toUri();
            return ResponseEntity.status(HttpStatus.FOUND).location(redirect)
                    .header(HttpHeaders.SET_COOKIE, txnCookie(txn, FLOW_TTL).toString()).build();
        } catch (Exception e) {
            log.warn("SSO start failed for {}: {}", orgSlug, e.toString());
            return failure("sso_unavailable");
        } finally {
            TenantContext.clear();
        }
    }

    @NotAudited
    @GetMapping("/sso/callback")
    public ResponseEntity<?> callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
                                      @CookieValue(name = TXN_COOKIE, required = false) String txn) {
        var claims = state == null ? null : jwt.parseClaimsToken(state, STATE_PURPOSE).orElse(null);
        if (code == null || claims == null || txn == null
                || !MessageDigest.isEqual(sha256Hex(txn).getBytes(StandardCharsets.US_ASCII),
                String.valueOf(claims.get("bh", String.class)).getBytes(StandardCharsets.US_ASCII))) {
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
            JsonNode meta = discovery(config.issuer());
            var form = new LinkedMultiValueMap<String, String>();
            form.add("grant_type", "authorization_code");
            form.add("code", code);
            form.add("redirect_uri", callbackUri());
            form.add("client_id", config.clientId());
            form.add("client_secret", config.clientSecret());
            form.add("code_verifier", pkceVerifier(txn));
            String tokenBody = http.restClient().post().uri(urlGuard.check(text(meta, "token_endpoint")))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(String.class);
            String idToken = text(mapper.readTree(tokenBody), "id_token");

            Jwt id = decoder(config.issuer(), text(meta, "jwks_uri")).decode(idToken);
            if (id.getAudience() == null || !id.getAudience().contains(config.clientId())
                    || !Objects.equals(nonce, id.getClaimAsString("nonce"))) {
                return failure("sso_failed");
            }
            User user = resolveAccount(orgId, config.issuer(), id);
            if (user == null || !user.isUsable()) {
                return failure("no_account");
            }
            String handoff = randomToken();
            jdbc.update("INSERT INTO sso_handoff (code_hash, tenant_id, username, expires_at) VALUES (?, ?, ?, ?)",
                    sha256Hex(handoff), orgId, user.getUsername(), java.sql.Timestamp.from(Instant.now().plus(HANDOFF_TTL)));
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, frontendUrl + "/sso-complete#code=" + handoff)
                    .header(HttpHeaders.SET_COOKIE, txnCookie("", Duration.ZERO).toString()).build();
        } catch (Exception e) {
            log.warn("SSO callback failed: {}", e.toString());
            return failure("sso_failed");
        } finally {
            TenantContext.clear();
        }
    }

    /** By linked (issuer, subject) first; otherwise link an existing account once, by email. */
    private User resolveAccount(Long orgId, String issuer, Jwt id) {
        String subject = id.getSubject();
        if (subject == null || subject.isBlank()) {
            return null;
        }
        var linked = users.findBySsoIdentity(orgId, issuer, subject);
        if (linked.isPresent()) {
            return linked.get();
        }
        String email = id.getClaimAsString("email");
        Boolean verified = id.getClaimAsBoolean("email_verified");
        if (email == null || email.isBlank() || Boolean.FALSE.equals(verified)) {
            return null;
        }
        User byEmail = users.findByOrganizationAndEmail(orgId, email.trim()).orElse(null);
        if (byEmail == null || byEmail.getSsoSubject() != null) {
            // Already linked to a different identity: an email match must not re-point it.
            return null;
        }
        byEmail.linkSsoIdentity(issuer, subject);
        return users.save(byEmail);
    }

    public record ExchangeRequest(@NotBlank @Size(max = 100) String code) {
    }

    /** Single-use: the row is deleted as it is read, so a code can mint at most one session. */
    @NotAudited
    @PostMapping("/sso/exchange")
    public ResponseEntity<?> exchange(@Valid @RequestBody ExchangeRequest request, HttpServletRequest httpRequest) {
        var throttled = throttle.check(AuthThrottle.Kind.LOGIN, httpRequest);
        if (throttled.isPresent()) {
            return throttled.get();
        }
        jdbc.update("DELETE FROM sso_handoff WHERE expires_at < now() - interval '1 hour'");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "DELETE FROM sso_handoff WHERE code_hash = ? AND expires_at > now() RETURNING tenant_id, username",
                sha256Hex(request.code()));
        if (rows.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Sign-in link expired. Please try again."));
        }
        Long orgId = ((Number) rows.get(0).get("tenant_id")).longValue();
        String username = (String) rows.get(0).get("username");
        TenantContext.set(orgId);
        try {
            User user = users.findByOrganizationAndUsername(orgId, username).filter(User::isUsable).orElse(null);
            if (user == null || !organizations.isActive(orgId)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Account unavailable"));
            }
            return ResponseEntity.ok(AuthController.LoginResponse.of(user, jwt.issueSession(user)));
        } finally {
            TenantContext.clear();
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private ResponseEntity<?> failure(String code) {
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, frontendUrl + "/login?ssoError=" + code).build();
    }

    private String callbackUri() {
        return publicUrl + "/v1/sso/callback";
    }

    private ResponseCookie txnCookie(String value, Duration maxAge) {
        return ResponseCookie.from(TXN_COOKIE, value).httpOnly(true).secure(publicUrl.startsWith("https://"))
                .sameSite("Lax").path("/v1/sso/callback").maxAge(maxAge).build();
    }

    private JsonNode discovery(String issuer) {
        Cached<JsonNode> hit = discoveryCache.get(issuer);
        if (hit != null && hit.expiresAt().isAfter(Instant.now())) {
            return hit.value();
        }
        JsonNode meta = fetchDiscovery(issuer);
        discoveryCache.put(issuer, new Cached<>(meta, Instant.now().plus(DISCOVERY_TTL)));
        return meta;
    }

    private JsonNode fetchDiscovery(String issuer) {
        String url = issuer.endsWith("/") ? issuer + ".well-known/openid-configuration" : issuer + "/.well-known/openid-configuration";
        return mapper.readTree(http.restClient().get().uri(urlGuard.check(url)).retrieve().body(String.class));
    }

    /** One decoder per JWKS endpoint, reused so Nimbus's own key cache works across logins. */
    private NimbusJwtDecoder decoder(String issuer, String jwksUri) {
        String key = issuer + "|" + jwksUri;
        Cached<NimbusJwtDecoder> hit = decoderCache.get(key);
        if (hit != null && hit.expiresAt().isAfter(Instant.now())) {
            return hit.value();
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(urlGuard.check(jwksUri).toString())
                .restOperations(new RestTemplate(http.requestFactory())).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        decoderCache.put(key, new Cached<>(decoder, Instant.now().plus(DISCOVERY_TTL)));
        return decoder;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Missing '" + field + "' in provider response");
        }
        return value.asString();
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return base64Url(bytes);
    }

    private static String pkceVerifier(String txn) {
        return base64Url(sha256("pkce:" + txn));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256Hex(String value) {
        return HexFormat.of().formatHex(sha256(value));
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
