package com.arthadhruva.riskengine.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** Reads {@code Authorization: Bearer <token>}, validates it, and populates the security
 * context; requests with no or an invalid token simply proceed unauthenticated (Spring
 * Security's own authorization rules then reject them if the target endpoint requires it).
 *
 * A valid signature alone isn't enough to authenticate: this also looks the user up and checks
 * {@code enabled}/{@code isCurrentlyLocked()} on every request, not just at login. Without that,
 * deactivating or locking an account would only block *future* logins -- a token issued before
 * the change would keep working for its full remaining lifetime (up to jwt.expiration-hours),
 * since a JWT's signature alone can't reflect account state that changed after it was issued.
 * A failed check here just leaves the request unauthenticated, which the existing
 * anyRequest().authenticated() chain already turns into a 401 -- identical to an expired or
 * invalid token, so the frontend's existing "401 -> session expired" handling covers this case
 * with no extra code.
 *
 * A setup-purpose token (see JwtService#issueSetupToken) gets a distinct ROLE_TOTP_SETUP
 * authority instead of the user's real role -- SecurityConfig only permits that authority on the
 * two TOTP enrollment endpoints, so a token minted to bootstrap 2FA enrollment can't be used for
 * anything else even though it's technically a valid, authenticated credential. */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            jwtService.parse(token).ifPresent(parsed -> {
                userRepository.findByUsername(parsed.username())
                        .filter(User::isEnabled)
                        .filter(user -> !user.isCurrentlyLocked())
                        .ifPresent(user -> {
                            String authority = parsed.isSetupOnly() ? "ROLE_TOTP_SETUP" : "ROLE_" + parsed.role().name();
                            var authorities = List.of(new SimpleGrantedAuthority(authority));
                            var authentication = new UsernamePasswordAuthenticationToken(parsed.username(), null, authorities);
                            SecurityContextHolder.getContext().setAuthentication(authentication);
                        });
            });
        }
        filterChain.doFilter(request, response);
    }
}
