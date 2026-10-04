package com.arthadhruva.riskengine.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestHeaderRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Stateless JWT auth: no server-side {@code HttpSession} (see {@code SessionCreationPolicy.STATELESS}
 * below). The session token itself does live in a cookie -- the httpOnly {@code ad_session} cookie
 * JwtAuthenticationFilter reads (see SessionCookie) -- so a browser attaches it to requests by itself, and
 * CSRF is defended in two layers. First, that cookie is {@code SameSite=Strict}, so it is never sent on a
 * cross-<em>site</em> request. That does not cover a same-<em>site</em> page (another subdomain of the same
 * registrable domain, which a per-tenant-subdomain deployment has), so second, every unsafe request must
 * also carry the double-submit token: the script-readable {@code XSRF-TOKEN} cookie
 * (see {@link #csrfTokenRepository}) echoed in an {@code X-XSRF-TOKEN} header, which a page on another origin
 * cannot read or set. A browser never attaches a custom header by itself either, so the two requests that
 * authenticate with a header credential instead -- an {@code X-API-Key} call to {@code /v1/ingest/**} and the
 * {@code Authorization: Bearer} TOTP-setup calls -- are not token-protected: there is nothing ambient to ride.
 * The login page and the other public POSTs are protected like the rest (a forged login is a login-CSRF).
 * A page with no token yet gets one from {@link CsrfCookieFilter}, which attaches it to <em>any</em> response,
 * so the frontend fetches {@code GET /v1/csrf} and reads the cookie. That path is deliberately not a real,
 * public endpoint: unauthenticated it answers 401, and idle-stop.sh counts a 2xx {@code /v1/} response as a
 * visitor, so an anonymous 2xx GET would let any crawler keep the server awake (and billing).
 *
 * <p>Known limit: the token is a plain double-submit value, neither signed nor bound to the session, and the
 * server keeps no copy. It stops a page that can <em>send</em> requests as the user but cannot read this origin's
 * cookies (a cross-site page, a sibling subdomain's page). It does not stop an attacker who can also <em>set</em>
 * cookies for this site (cookie tossing, from script running on a sibling subdomain): they can pair a cookie of
 * their choosing with a matching header. Closing that would mean signing the token with a server key and binding
 * it to the session; not done here. The token is also not rotated at login (no session is created), so Spring's
 * rotation-on-authentication is switched off below.
 *
 * <p>The one genuinely cross-site leg in the whole app, the SSO identity-provider redirect, never carries the session cookie -- it uses its own
 * separate, narrowly-scoped {@code sso_txn} cookie instead (SsoController).
 * {@code /login}, {@code /activate}
 * (a CLIENT completing an admin-issued invite -- see ActivationController), and the actuator
 * health/prometheus endpoints (Prometheus itself carries no bearer token) are public; {@code
 * /admin/**} requires the ADMIN role; {@code /my/**} (a CLIENT's
 * own-loan view) and {@code /account/**} (self-service actions like changing your own password)
 * require being logged in as a real role (ANALYST/ADMIN/CLIENT); everything else -- the scoring/
 * analysis tools -- requires ANALYST or ADMIN specifically, excluding CLIENT: a borrower can see
 * their own loan's score, but can't submit new applications or run any analysis tool.
 *
 * The two TOTP enrollment endpoints are the one exception carved out of {@code /account/**}'s
 * real-role requirement: they also accept {@code ROLE_TOTP_SETUP}, the narrow authority
 * JwtAuthenticationFilter assigns to a short-lived setup token (see JwtService#issueSetupToken).
 * That's deliberate and load-bearing -- {@code /my/**} and the rest of {@code /account/**} used
 * to just require {@code authenticated()} (any authentication at all, regardless of role), which
 * would have let a setup token -- meant to reach only these two endpoints -- pass those checks
 * too, since it does carry a valid, authenticated principal. Requiring a real role everywhere
 * else is what actually contains it.
 *
 * Explicit exception handling below matters: without it, Spring Security's default entry point
 * for an API with neither httpBasic() nor formLogin() configured returns 403 for *every* auth
 * failure, missing token included -- verified this live (curl with no Authorization header came
 * back 403, not 401). That breaks the frontend's "401 -> session expired, redirect to login"
 * handling, which specifically needs 401 reserved for "not authenticated" and 403 for
 * "authenticated but not permitted" (e.g. an ANALYST hitting /admin/**).
 *
 * {@code /error} is deliberately permitted too -- also verified live, the hard way: calling
 * {@code response.sendError(403, ...)} from the accessDeniedHandler below triggers Spring Boot's
 * default error-page forwarding, an internal FORWARD dispatch to {@code /error}. Spring Security
 * filters (including JwtAuthenticationFilter) only run on the original REQUEST dispatch, not on
 * that forward, so {@code /error} arrived with no Authorization header processed and no security
 * context -- which then failed {@code anyRequest().authenticated()} a *second* time and the
 * authenticationEntryPoint silently overwrote the already-correct 403 with 401. Without
 * permitting {@code /error}, every non-2xx response from any endpoint -- not just role checks --
 * would come back as 401 regardless of its real cause.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Work factor 12 (2^12 rounds, four times the library default of 10): slower offline cracking of
     * a leaked hash at a login cost the per-address throttle keeps bounded. Existing cost-10 hashes still
     * verify, since each hash records its own cost. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * {@code .accountLocked(...)} / {@code .disabled(...)} here is what makes lockout and
     * deactivation real: DaoAuthenticationProvider (used internally by AuthenticationManager)
     * checks account-locked, then enabled, *before* comparing the password, throwing
     * LockedException / DisabledException instead of BadCredentialsException -- so a locked or
     * deactivated account is rejected without the submitted password ever being verified, and
     * AuthController can distinguish all three cases cleanly.
     *
     * <p>The {@code username} this bean receives is really {@code orgId::username} (see
     * AuthController#compositePrincipal) -- Spring Security's {@code UserDetailsService} contract
     * only carries a single string, and {@code username} alone is no longer enough to find a
     * unique row once it's only unique per-tenant (see {@link User}'s class doc). The returned
     * {@code UserDetails}' own username is set back to the real (non-composite) username, since
     * that's what ends up in {@code Authentication#getName()} everywhere downstream.
     */
    @Bean
    public UserDetailsService userDetailsService(UserRepository userRepository) {
        return compositePrincipal -> {
            String[] parts = compositePrincipal.split("::", 2);
            if (parts.length != 2) {
                throw new UsernameNotFoundException("Malformed principal");
            }
            Long organizationId = Long.valueOf(parts[0]);
            String username = parts[1];
            return userRepository.findByOrganizationIdAndUsername(organizationId, username)
                    .map(u -> org.springframework.security.core.userdetails.User
                            .withUsername(u.getUsername())
                            .password(u.getPasswordHash())
                            .authorities("ROLE_" + u.getRole().name())
                            .accountLocked(u.isCurrentlyLocked())
                            .disabled(!u.isEnabled())
                            .build())
                    .orElseThrow(() -> new UsernameNotFoundException("Unknown user: " + username));
        };
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * The double-submit token: an {@code XSRF-TOKEN} cookie a script on this origin can read (so not httpOnly,
     * unlike the session cookie), echoed by the frontend in {@code X-XSRF-TOKEN}. {@code SameSite=Strict} and
     * {@code Secure} on https mirror SessionCookie. The path is {@code /}, not {@code /v1}: a cookie is visible
     * to a page's script only when the page's own path is under the cookie's, and the app's pages are not under
     * {@code /v1}. {@code setCookieCustomizer} replaces what {@code withHttpOnlyFalse()} installs, so
     * {@code httpOnly(false)} is repeated here.
     */
    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository(@Value("${app.public-url:http://localhost:8080}") String publicUrl) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        repository.setCookieCustomizer(cookie -> cookie.httpOnly(false).sameSite("Strict")
                .secure(publicUrl.startsWith("https://")));
        return repository;
    }

    /**
     * Requests whose credential travels in a header the browser never adds by itself, so there is no ambient
     * credential to ride and no token is asked of them: an {@code X-API-Key} call to {@code /v1/ingest/**}, and
     * the two TOTP-setup calls made with an {@code Authorization: Bearer} setup token. A page on another origin
     * cannot send either header without a CORS preflight, which the allow-list refuses. Both conditions must
     * hold, so a session-cookie request to the same paths is still token-protected.
     */
    private static RequestMatcher[] headerCredentialRequests() {
        RequestMatcher apiKeyIngest = new AndRequestMatcher(
                PathPatternRequestMatcher.pathPattern("/v1/ingest/**"),
                new RequestHeaderRequestMatcher("X-API-Key"));
        RequestMatcher bearerSetup = new AndRequestMatcher(
                new OrRequestMatcher(
                        PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/v1/account/2fa/setup"),
                        PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/v1/account/2fa/confirm")),
                request -> {
                    String authorization = request.getHeader("Authorization");
                    return authorization != null && authorization.startsWith("Bearer ");
                });
        return new RequestMatcher[] {apiKeyIngest, bearerSetup};
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, CookieCsrfTokenRepository csrfTokenRepository,
                                                   JwtAuthenticationFilter jwtFilter,
                                                   com.arthadhruva.riskengine.apikey.ApiKeyAuthenticationFilter apiKeyFilter,
                                                   com.arthadhruva.riskengine.ratelimit.RateLimitFilter rateLimitFilter,
                                                   com.arthadhruva.riskengine.idempotency.IdempotencyFilter idempotencyFilter) throws Exception {
        http
                .cors(Customizer.withDefaults())
                // See the class javadoc: SameSite=Strict plus a double-submit token on every unsafe request. The
                // plain (non-XOR) handler is deliberate: the frontend echoes the cookie's value verbatim.
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        // Spring rotates the token (expiring the cookie) whenever a request authenticates. Every
                        // signed-in request authenticates afresh here (the JWT filter, no HttpSession), so by
                        // default every one of them deleted the browser's token and a busy page raced itself.
                        // Rotation is a session-fixation defense for a login that creates a session; there is none.
                        .sessionAuthenticationStrategy((authentication, request, response) -> { })
                        .ignoringRequestMatchers(headerCredentialRequests()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, authException) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden")))
                .authorizeHttpRequests(auth -> auth
                        // An ASYNC dispatch is the container resuming a request that already passed these rules
                        // on its REQUEST dispatch (a streamed export finishing). The token filters do not run a
                        // second time, so without this every export was denied after its body had been written.
                        // A client cannot start an ASYNC dispatch; only the server does, for a request it accepted.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers("/v1/login", "/v1/access-requests", "/v1/sso/**", "/v1/password-reset/request", "/v1/password-reset/complete", "/v1/activate", "/actuator/health", "/actuator/health/**", "/actuator/prometheus", "/error").permitAll()
                        .requestMatchers("/v1/ingest/**").hasRole("API_INGEST")
                        .requestMatchers("/v1/platform/**").hasRole("PLATFORM_ADMIN")
                        .requestMatchers("/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/v1/account/2fa/setup", "/v1/account/2fa/confirm")
                                .hasAnyRole("TOTP_SETUP", "ANALYST", "ADMIN", "CLIENT", "PLATFORM_ADMIN")
                        .requestMatchers("/v1/account/**").hasAnyRole("ANALYST", "ADMIN", "CLIENT", "PLATFORM_ADMIN")
                        .requestMatchers("/v1/my/**").hasAnyRole("ANALYST", "ADMIN", "CLIENT")
                        .anyRequest().hasAnyRole("ANALYST", "ADMIN"))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(apiKeyFilter, JwtAuthenticationFilter.class)
                .addFilterAfter(rateLimitFilter, com.arthadhruva.riskengine.apikey.ApiKeyAuthenticationFilter.class)
                // After authorization, not before it: a stored response is only ever replayed to a caller
                // who is allowed to call the endpoint in the first place.
                .addFilterAfter(idempotencyFilter, org.springframework.security.web.access.intercept.AuthorizationFilter.class);
        return http.build();
    }
}
