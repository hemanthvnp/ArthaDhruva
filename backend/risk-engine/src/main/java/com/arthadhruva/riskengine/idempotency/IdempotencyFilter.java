package com.arthadhruva.riskengine.idempotency;

import com.arthadhruva.riskengine.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Opt-in idempotency for POST/PATCH: send {@code Idempotency-Key: <unique id>} and a retry of the same
 * request executes at most once; the first response is stored and replayed with
 * {@code Idempotent-Replay: true}. A duplicate arriving mid-flight gets 409, and the same key reused with
 * a different request gets 422. Server errors are not stored, so they can be retried.
 *
 * <ul>
 *   <li>Runs after authorization (SecurityConfig): a stored response is only replayed to a caller who
 *       may call the endpoint at all.</li>
 *   <li>Keys are scoped to (tenant, principal).</li>
 *   <li>A response containing credential-like fields (a newly issued API key or webhook secret, say) is
 *       recorded by status only; its body is never persisted, and a replay says so.</li>
 * </ul>
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

    static final String HEADER = "Idempotency-Key";
    static final int MAX_BODY_BYTES = 64 * 1024;
    private static final Pattern VALID_KEY = Pattern.compile("[\\x21-\\x7e]{1,120}");
    private static final Pattern SENSITIVE_FIELD = Pattern.compile(
            "(?i).*(passw|secret|token|credential|activationlink|totp|qrcode).*|(?i).*key");

    private final IdempotencyStore store;
    private final ObjectMapper mapper = new ObjectMapper();

    public IdempotencyFilter(IdempotencyStore store) {
        this.store = store;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        String type = request.getContentType();
        return !("POST".equals(method) || "PATCH".equals(method))
                || request.getHeader(HEADER) == null
                || TenantContext.getOptional().isEmpty()
                || (type != null && type.toLowerCase().startsWith("multipart/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Long tenantId = TenantContext.get();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String principal = auth == null ? "" : auth.getName();
        String key = request.getHeader(HEADER).trim();
        if (!VALID_KEY.matcher(key).matches()) {
            response.sendError(400, "Idempotency-Key must be 1-120 printable characters");
            return;
        }

        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            response.sendError(413, "Body too large for an idempotent request");
            return;
        }
        String hash = sha256(request.getMethod() + " " + request.getRequestURI() + "\n", body);
        UUID owner = UUID.randomUUID();

        if (!store.claim(tenantId, principal, key, hash, owner) && !store.reclaimIfStale(tenantId, principal, key, hash, owner)) {
            IdempotencyStore.Stored existing = store.find(tenantId, principal, key);
            if (existing == null) {
                response.setHeader("Retry-After", "1");
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
                store.release(tenantId, principal, key, owner);
            } else {
                String responseBody = new String(wrapped.getContentAsByteArray(), StandardCharsets.UTF_8);
                boolean withheld = containsCredentials(responseBody);
                store.complete(tenantId, principal, key, owner, status, wrapped.getContentType(),
                        withheld ? null : responseBody, withheld);
            }
            completed = true;
        } finally {
            if (!completed) {
                store.release(tenantId, principal, key, owner);
            }
            wrapped.copyBodyToResponse();
        }
    }

    private boolean containsCredentials(String json) {
        if (json.isEmpty()) {
            return false;
        }
        try {
            return hasSensitiveField(mapper.readTree(json));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasSensitiveField(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                if (SENSITIVE_FIELD.matcher(field.getKey()).matches() && !field.getValue().isNull()) {
                    return true;
                }
                if (hasSensitiveField(field.getValue())) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (hasSensitiveField(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void replay(HttpServletResponse response, IdempotencyStore.Stored stored) throws IOException {
        response.setStatus(stored.status() == null ? 200 : stored.status());
        response.setHeader("Idempotent-Replay", "true");
        if (stored.bodyWithheld()) {
            response.setContentType("application/json");
            response.getOutputStream().write(("{\"replayed\":true,\"note\":\"The original response contained credentials, "
                    + "which are shown only once and never stored.\"}").getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (stored.contentType() != null) {
            response.setContentType(stored.contentType());
        }
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
                @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
