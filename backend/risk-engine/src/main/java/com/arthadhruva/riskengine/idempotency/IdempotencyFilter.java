package com.arthadhruva.riskengine.idempotency;

import com.arthadhruva.riskengine.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Opt-in idempotency for mutating requests: send {@code Idempotency-Key: <unique id>} on a POST and
 * a retry of that same request (network timeout, client crash, double-click) is executed at most
 * once. The first execution's response is stored; a duplicate gets it replayed with
 * {@code Idempotent-Replay: true}. A duplicate that arrives while the first is still running gets
 * 409, and the same key reused with a different body gets 422 (a client bug, refused loudly).
 * Keys are per tenant. 5xx responses are not stored, so a genuine failure can be retried.
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

    static final String HEADER = "Idempotency-Key";
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int RETENTION_HOURS = 24;

    private final IdempotencyStore store;

    public IdempotencyFilter(IdempotencyStore store) {
        this.store = store;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String type = request.getContentType();
        return !"POST".equals(request.getMethod())
                || request.getHeader(HEADER) == null
                || TenantContext.getOptional().isEmpty()
                || (type != null && type.toLowerCase().startsWith("multipart/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Long tenantId = TenantContext.get();
        String key = request.getHeader(HEADER).trim();
        if (key.isEmpty() || key.length() > 120) {
            response.sendError(400, "Idempotency-Key must be 1-120 characters");
            return;
        }

        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            response.sendError(413, "Body too large for an idempotent request");
            return;
        }
        String hash = sha256(request.getRequestURI() + "\n" + body.length + "\n", body);

        if (ThreadLocalRandom.current().nextInt(100) == 0) {
            store.purgeOlderThanHours(RETENTION_HOURS);
        }

        if (!store.claim(tenantId, key, hash) && !(store.reclaimIfStale(tenantId, key))) {
            IdempotencyStore.Stored existing = store.find(tenantId, key);
            if (existing == null) {
                response.sendError(409, "Idempotency key is being released; retry");
            } else if (!existing.requestHash().equals(hash)) {
                response.sendError(422, "Idempotency-Key was already used with a different request");
            } else if ("DONE".equals(existing.state())) {
                replay(response, existing);
            } else {
                response.setHeader("Retry-After", "1");
                response.sendError(409, "A request with this Idempotency-Key is still in progress");
            }
            return;
        }

        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        boolean completed = false;
        try {
            chain.doFilter(new CachedBodyRequest(request, body), wrapped);
            int status = wrapped.getStatus();
            if (status >= 500) {
                store.release(tenantId, key);
            } else {
                store.complete(tenantId, key, status, wrapped.getContentType(),
                        new String(wrapped.getContentAsByteArray(), StandardCharsets.UTF_8));
            }
            completed = true;
        } finally {
            if (!completed) {
                store.release(tenantId, key);
            }
            wrapped.copyBodyToResponse();
        }
    }

    private static void replay(HttpServletResponse response, IdempotencyStore.Stored stored) throws IOException {
        response.setStatus(stored.status() == null ? 200 : stored.status());
        if (stored.contentType() != null) {
            response.setContentType(stored.contentType());
        }
        response.setHeader("Idempotent-Replay", "true");
        if (stored.body() != null) {
            response.getOutputStream().write(stored.body().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String sha256(String prefix, byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(prefix.getBytes(StandardCharsets.UTF_8));
            md.update(body);
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The body was consumed to hash it; this hands the same bytes to the real handler. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return in.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener l) { }
                @Override public int read() { return in.read(); }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
