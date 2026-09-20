package com.arthadhruva.riskengine.apikey;

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
 * Authenticates {@code X-API-Key: ak_...} requests. A valid key yields the single authority
 * {@code ROLE_API_INGEST} and the key's tenant; SecurityConfig grants that authority only to
 * {@code /v1/ingest/**}, so a leaked key cannot read cases, manage users or reach any other
 * endpoint. An invalid or revoked key authenticates nothing, identical to sending no key.
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    static final String HEADER = "X-API-Key";
    private final ApiKeyService keys;

    public ApiKeyAuthenticationFilter(ApiKeyService keys) {
        this.keys = keys;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean tenantSet = false;
        String presented = request.getHeader(HEADER);
        if (presented != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            var auth = keys.authenticate(presented);
            if (auth.isPresent()) {
                TenantContext.set(auth.get().tenantId());
                tenantSet = true;
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        "api-key:" + auth.get().keyId(), null, List.of(new SimpleGrantedAuthority("ROLE_API_INGEST"))));
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            if (tenantSet) {
                TenantContext.clear();
            }
        }
    }
}
