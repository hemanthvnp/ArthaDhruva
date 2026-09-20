package com.arthadhruva.riskengine.security;

import com.arthadhruva.riskengine.tenant.TenantContext;
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
    private final com.arthadhruva.riskengine.tenant.OrganizationService organizationService;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository,
                                   com.arthadhruva.riskengine.tenant.OrganizationService organizationService) {
        this.organizationService = organizationService;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // Tenant context must stay set for the *entire* downstream request (every controller and
        // repository call this request makes), not just the user lookup below -- hence wrapping
        // the filterChain.doFilter() call itself in the try/finally, not just this method's own
        // work. A token with no org claim (malformed, or predating multi-tenancy) leaves the
        // context unset entirely; the lookup below then finds nothing and the request proceeds
        // unauthenticated, same as any other invalid token.
        boolean tenantSet = false;
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            var parsed = jwtService.parse(token).orElse(null);
            // Activation and password-reset tokens are single-purpose links, never sessions: presenting one
            // as a bearer token authenticates nothing.
            if (parsed != null && parsed.organizationId() != null && !parsed.isActivationOnly() && !parsed.isResetOnly()) {
                TenantContext.set(parsed.organizationId());
                tenantSet = true;
                // A suspended organization authenticates nobody, even with a still-unexpired token.
                if (organizationService.isActive(parsed.organizationId())) {
                userRepository.findByOrganizationIdAndUsername(parsed.organizationId(), parsed.username())
                        .filter(User::isEnabled)
                        .filter(user -> !user.isCurrentlyLocked())
                        .ifPresent(user -> {
                            String authority = parsed.isSetupOnly() ? "ROLE_TOTP_SETUP" : "ROLE_" + parsed.role().name();
                            var authorities = List.of(new SimpleGrantedAuthority(authority));
                            var authentication = new UsernamePasswordAuthenticationToken(parsed.username(), null, authorities);
                            SecurityContextHolder.getContext().setAuthentication(authentication);
                        });
                }
            }
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (tenantSet) {
                TenantContext.clear();
            }
        }
    }
}
