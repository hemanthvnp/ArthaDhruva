package com.arthadhruva.riskengine.audit;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Serializes audit payloads with credentials removed. Every object field whose name looks like a
 * credential -- at any depth, in records and maps alike -- is replaced with {@code [REDACTED]}. This is
 * the second line of defense behind {@link NotAudited}: an endpoint someone forgets to exclude still
 * never writes a password, token, key or activation link into the audit table (or its backups).
 *
 * <p>A {@link ResponseEntity} is reduced to its body: headers such as {@code Location} can carry
 * tokens and are never stored. Output is capped so an export or a large list cannot bloat the trail.
 */
@Component
public class AuditRedactor {

    static final String REDACTED = "[REDACTED]";

    /** password, secret, token, credential, authorization, cookie, session, signature, activation
     * link, TOTP/OTP, QR code data, and any field named or ending in "key" or "code". */
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i).*(passw|secret|token|credential|authoriz|cookie|session|signature|activationlink|totp|otp|qrcode).*"
                    + "|(?i).*key|(?i).*code");

    private final ObjectMapper mapper = new ObjectMapper();

    /** Redacted JSON of {@code value}, truncated to {@code maxChars}; null if it cannot be serialized. */
    public String toJson(Object value, int maxChars) {
        if (value == null) {
            return null;
        }
        Object target = value instanceof ResponseEntity<?> response ? response.getBody() : value;
        if (target == null) {
            return null;
        }
        try {
            JsonNode tree = mapper.valueToTree(target);
            redact(tree);
            String json = mapper.writeValueAsString(tree);
            return json.length() <= maxChars ? json : json.substring(0, maxChars) + "...[truncated " + (json.length() - maxChars) + " chars]";
        } catch (Exception e) {
            return null;
        }
    }

    static boolean isSensitive(String fieldName) {
        return SENSITIVE.matcher(fieldName).matches();
    }

    private void redact(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> sensitive = new ArrayList<>();
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                if (isSensitive(field.getKey())) {
                    sensitive.add(field.getKey());
                } else {
                    redact(field.getValue());
                }
            }
            sensitive.forEach(name -> object.put(name, REDACTED));
        } else if (node != null && node.isArray()) {
            for (JsonNode child : node) {
                redact(child);
            }
        }
    }
}
