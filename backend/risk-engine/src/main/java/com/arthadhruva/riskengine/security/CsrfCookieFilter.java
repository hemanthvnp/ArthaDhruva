package com.arthadhruva.riskengine.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Makes sure the browser receives the {@code XSRF-TOKEN} cookie. Spring Security loads the CSRF token lazily,
 * so unless something reads it the repository never writes the cookie, and a single-page app that only ever
 * reads cookies would never get one. Reading it here, after {@code CsrfFilter} has registered it as a request
 * attribute, writes the cookie once per browser (the repository only saves a token it had to generate) and
 * costs nothing on later requests, which already carry it.
 *
 * <p>It runs before authorization, so even a 401 carries the cookie. The frontend relies on that: a browser with
 * no token yet requests {@code GET /v1/csrf}, which has no handler and, unauthenticated, answers 401 with the
 * cookie attached. It must stay a non-2xx: idle-stop.sh counts a 2xx {@code /v1/} response as a visitor.
 */
final class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token != null) {
            token.getToken();
        }
        chain.doFilter(request, response);
    }
}
