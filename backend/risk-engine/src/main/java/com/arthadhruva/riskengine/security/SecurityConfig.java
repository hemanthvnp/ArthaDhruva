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
 * for disabling CSRF protection on a stateless token API). {@code /login} and the actuator
 * health check are public; {@code /admin/**} requires the ADMIN role; everything else just
 * requires being logged in.
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

    @Bean
    public UserDetailsService userDetailsService(UserRepository userRepository) {
        return username -> userRepository.findByUsername(username)
                .map(u -> org.springframework.security.core.userdetails.User
                        .withUsername(u.getUsername())
                        .password(u.getPasswordHash())
                        .authorities("ROLE_" + u.getRole().name())
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user: " + username));
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
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
                        .requestMatchers("/login", "/actuator/health", "/error").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
