package com.arthadhruva.riskengine.audit;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditRedactorTest {

    private final AuditRedactor redactor = new AuditRedactor();

    private record Login(String username, String password, String totpCode) {
    }

    private record Created(String name, String apiKey, Nested nested, List<Nested> history) {
    }

    private record Nested(String label, String refreshToken) {
    }

    @Test
    void credentialsAreRemovedAtEveryDepth() {
        String json = redactor.toJson(new Created("ci", "ak_live_123", new Nested("a", "t1"),
                List.of(new Nested("b", "t2"), new Nested("c", "t3"))), 10_000);
        assertFalse(json.contains("ak_live_123"));
        assertFalse(json.contains("t1") || json.contains("t2") || json.contains("t3"));
        assertTrue(json.contains("\"name\":\"ci\"") && json.contains("\"label\":\"b\""), "ordinary fields are kept: " + json);
        assertEquals(4, json.split(java.util.regex.Pattern.quote(AuditRedactor.REDACTED), -1).length - 1);
    }

    @Test
    void mapsAreRedactedLikeRecords() {
        String json = redactor.toJson(Map.of("activationLink", "https://x/activate?token=abc", "message", "ok"), 10_000);
        assertFalse(json.contains("abc"));
        assertTrue(json.contains("\"message\":\"ok\""));
    }

    @Test
    void aResponseEntityIsReducedToItsBody() {
        ResponseEntity<Login> response = ResponseEntity.ok().header("Location", "/sso-complete#code=secret-code")
                .body(new Login("ana", "hunter2", "123456"));
        String json = redactor.toJson(response, 10_000);
        assertFalse(json.contains("hunter2") || json.contains("123456") || json.contains("secret-code"));
        assertTrue(json.contains("\"username\":\"ana\""));
        assertNull(redactor.toJson(ResponseEntity.noContent().build(), 100));
        assertNull(redactor.toJson(null, 100));
    }

    @Test
    void longPayloadsAreTruncatedWithANote() {
        String json = redactor.toJson(Map.of("text", "x".repeat(5_000)), 100);
        assertTrue(json.startsWith("{\"text\":\"xxx"));
        assertTrue(json.endsWith(" chars]"));
        assertTrue(json.length() < 150);
    }

    @Test
    void fieldNamesAreJudgedByWhatTheyLookLike() {
        for (String sensitive : List.of("password", "newPassword", "clientSecret", "token", "accessToken", "apiKey", "key",
                "code", "totpCode", "qrCodeDataUri", "signature", "authorization", "sessionId", "activationLink", "otp")) {
            assertTrue(AuditRedactor.isSensitive(sensitive), sensitive);
        }
        for (String ordinary : List.of("username", "loanId", "creditScore", "status", "reasonCodes", "keyboard", "codes", "reason")) {
            assertFalse(AuditRedactor.isSensitive(ordinary), ordinary);
        }
    }
}
