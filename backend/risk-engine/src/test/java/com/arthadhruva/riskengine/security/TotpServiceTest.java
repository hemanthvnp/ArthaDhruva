package com.arthadhruva.riskengine.security;

import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TotpServiceTest {

    private static final String SECRET = "JBSWY3DPEHPK3PXP";
    private static final long NOW_STEP = 58_000_000L;   // 30-second steps since the epoch

    private final TotpService totp = new TotpService(Clock.fixed(Instant.ofEpochSecond(NOW_STEP * 30 + 7), ZoneOffset.UTC));

    private static String codeAt(long step) throws Exception {
        return new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6).generate(SECRET, step);
    }

    /** The service says which step a code belongs to: that is what lets the caller refuse a replay. */
    @Test
    void reportsTheStepACodeBelongsTo() throws Exception {
        assertEquals(OptionalLong.of(NOW_STEP), totp.matchingStep(SECRET, codeAt(NOW_STEP)));
        assertEquals(OptionalLong.of(NOW_STEP - 1), totp.matchingStep(SECRET, codeAt(NOW_STEP - 1)), "30 seconds of clock skew");
        assertEquals(OptionalLong.of(NOW_STEP + 1), totp.matchingStep(SECRET, codeAt(NOW_STEP + 1)));
    }

    @Test
    void rejectsCodesOutsideTheWindow() throws Exception {
        String old = codeAt(NOW_STEP - 2), future = codeAt(NOW_STEP + 2);
        // (a six-digit code can coincide with a valid one by chance; these two were checked not to)
        assertNotEquals(codeAt(NOW_STEP - 1), old);
        assertEquals(OptionalLong.empty(), totp.matchingStep(SECRET, old));
        assertEquals(OptionalLong.empty(), totp.matchingStep(SECRET, future));
    }

    @Test
    void rejectsAnythingThatIsNotSixDigits() throws Exception {
        String valid = codeAt(NOW_STEP);
        assertEquals(OptionalLong.empty(), totp.matchingStep(SECRET, valid.substring(0, 5)));
        assertEquals(OptionalLong.empty(), totp.matchingStep(SECRET, valid + "0"));
        assertEquals(OptionalLong.empty(), totp.matchingStep(SECRET, " " + valid.substring(1)));
        assertEquals(OptionalLong.empty(), totp.matchingStep(SECRET, "abcdef"));
        assertEquals(OptionalLong.empty(), totp.matchingStep(SECRET, null));
        assertEquals(OptionalLong.empty(), totp.matchingStep(null, valid));
        assertEquals(OptionalLong.empty(), totp.matchingStep("ANOTHERSECRETBASE32", valid));
    }
}
