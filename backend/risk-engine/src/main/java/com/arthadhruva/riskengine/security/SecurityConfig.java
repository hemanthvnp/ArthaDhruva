package com.arthadhruva.riskengine.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

import jakarta.servlet.http.HttpServletResponse;

/**
 * Stateless JWT bearer-token auth: no sessions, no cookies, CSRF disabled (there's no
 * cookie-based session for a cross-site request to ride along on -- the standard justification
 * for disabling CSRF protection on a stateless token API). {@code /login}, {@code /activate}
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

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
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

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter,
                                                   com.arthadhruva.riskengine.apikey.ApiKeyAuthenticationFilter apiKeyFilter,
                                                   com.arthadhruva.riskengine.ratelimit.RateLimitFilter rateLimitFilter,
                                                   com.arthadhruva.riskengine.idempotency.IdempotencyFilter idempotencyFilter) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, authException) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/v1/login", "/v1/signup", "/v1/sso/**", "/v1/password-reset/request", "/v1/password-reset/complete", "/v1/activate", "/actuator/health", "/actuator/prometheus", "/error").permitAll()
                        .requestMatchers("/v1/ingest/**").hasRole("API_INGEST")
                        .requestMatchers("/v1/platform/**").hasRole("PLATFORM_ADMIN")
                        .requestMatchers("/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/v1/account/2fa/setup", "/v1/account/2fa/confirm")
                                .hasAnyRole("TOTP_SETUP", "ANALYST", "ADMIN", "CLIENT")
                        .requestMatchers("/v1/my/**", "/v1/account/**").hasAnyRole("ANALYST", "ADMIN", "CLIENT")
                        .anyRequest().hasAnyRole("ANALYST", "ADMIN"))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(apiKeyFilter, JwtAuthenticationFilter.class)
                .addFilterAfter(rateLimitFilter, com.arthadhruva.riskengine.apikey.ApiKeyAuthenticationFilter.class)
                .addFilterAfter(idempotencyFilter, com.arthadhruva.riskengine.ratelimit.RateLimitFilter.class);
        return http.build();
    }
}
