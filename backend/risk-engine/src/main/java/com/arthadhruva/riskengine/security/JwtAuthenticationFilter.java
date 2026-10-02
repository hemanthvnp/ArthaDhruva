package com.arthadhruva.riskengine.security;

import com.arthadhruva.riskengine.tenant.OrganizationService;
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

/**
 * Reads {@code Authorization: Bearer <token>} and authenticates the request. Requests with no or an
 * invalid token proceed unauthenticated and SecurityConfig's rules reject them where needed.
 *
 * <p>Allow-list, not deny-list: only a session token or a TOTP-enrollment token can authenticate. Any
 * other purpose (activation, reset, SSO state, or one added later) authenticates nothing.
 *
 * <p>A valid signature is not enough. On every request the account is loaded and must be enabled,
 * activated, not locked, and (for sessions) carry the same {@code session_version} as the token --
 * which is how logout, password change, role change and deactivation revoke outstanding tokens
 * immediately. The granted authority comes from the account's current role in the database, never from
 * the token, so a demotion takes effect on the next request.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** The parsed token of an authenticated request, for endpoints that need its claims (refresh). */
    public static final String PARSED_TOKEN_ATTRIBUTE = JwtAuthenticationFilter.class.getName() + ".token";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final OrganizationService organizationService;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository, OrganizationService organizationService) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.organizationService = organizationService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // Tenant context must stay set for the whole downstream request, hence the try/finally around
        // filterChain.doFilter() itself.
        boolean tenantSet = false;
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            JwtService.ParsedToken parsed = jwtService.parse(header.substring(BEARER_PREFIX.length())).orElse(null);
            if (parsed != null && (parsed.isSession() || parsed.isSetupOnly()) && parsed.organizationId() != null) {
                TenantContext.set(parsed.organizationId());
                tenantSet = true;
                if (organizationService.isActive(parsed.organizationId())) {
                    userRepository.findByOrganizationIdAndUsername(parsed.organizationId(), parsed.username())
                            .filter(User::isUsable)
                            .filter(user -> parsed.isSetupOnly() || user.getSessionVersion() == parsed.sessionVersion())
                            // Defense in depth: the cross-tenant operator role is honored only for accounts in
                            // the platform organization, whatever a row in some tenant may say.
                            .filter(user -> user.getRole() != Role.PLATFORM_ADMIN
                                    || com.arthadhruva.riskengine.tenant.Organization.PLATFORM_SLUG.equals(user.getOrganization().getSlug()))
                            .ifPresent(user -> {
                                String authority = parsed.isSetupOnly() ? "ROLE_TOTP_SETUP" : "ROLE_" + user.getRole().name();
                                var authentication = new UsernamePasswordAuthenticationToken(
                                        user.getUsername(), null, List.of(new SimpleGrantedAuthority(authority)));
                                SecurityContextHolder.getContext().setAuthentication(authentication);
                                request.setAttribute(PARSED_TOKEN_ATTRIBUTE, parsed);
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
