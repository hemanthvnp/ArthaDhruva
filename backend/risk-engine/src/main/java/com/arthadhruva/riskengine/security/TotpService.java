package com.arthadhruva.riskengine.security;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.util.Utils;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.OptionalLong;

/**
 * Pure TOTP logic (RFC 6238, SHA-1, 6 digits, 30-second steps) on raw, decrypted secrets; callers own
 * encryption via {@link TotpSecretCipher}.
 *
 * <p>{@link #matchingStep} reports WHICH time step a code belongs to, so the caller can refuse a step
 * at or before the last one accepted (see UserRepository#claimTotpStep). The library's own verifier
 * only answers yes/no, which allows a captured code to be replayed for as long as it stays valid.
 */
@Service
public class TotpService {

    private static final String ISSUER = "ArthaDhruva";
    private static final int PERIOD_SECONDS = 30;
    /** One step either side of now: tolerates about 30 seconds of clock skew. */
    private static final int ALLOWED_DRIFT_STEPS = 1;

    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final QrGenerator qrGenerator = new ZxingPngQrGenerator();
    private final CodeGenerator codeGenerator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
    private final Clock clock;

    public TotpService() {
        this(Clock.systemUTC());
    }

    TotpService(Clock clock) {
        this.clock = clock;
    }

    public String generateSecret() {
        return secretGenerator.generate();
    }

    public String buildQrCodeDataUri(String username, String secret) {
        try {
            QrData data = new QrData.Builder()
                    .label(username)
                    .secret(secret)
                    .issuer(ISSUER)
                    .algorithm(HashingAlgorithm.SHA1)
                    .digits(6)
                    .period(PERIOD_SECONDS)
                    .build();
            return Utils.getDataUriForImage(qrGenerator.generate(data), qrGenerator.getImageMimeType());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate TOTP QR code", e);
        }
    }

    /** The time step the code is valid for (within the allowed drift), or empty if it matches none.
     * Every candidate is compared in constant time and the loop never exits early. */
    public OptionalLong matchingStep(String secret, String code) {
        if (secret == null || code == null || !code.matches("\\d{6}")) {
            return OptionalLong.empty();
        }
        long current = clock.millis() / 1000 / PERIOD_SECONDS;
        long matched = -1;
        byte[] presented = code.getBytes(StandardCharsets.US_ASCII);
        for (long step = current - ALLOWED_DRIFT_STEPS; step <= current + ALLOWED_DRIFT_STEPS; step++) {
            try {
                byte[] expected = codeGenerator.generate(secret, step).getBytes(StandardCharsets.US_ASCII);
                if (MessageDigest.isEqual(expected, presented) && matched < 0) {
                    matched = step;
                }
            } catch (CodeGenerationException e) {
                return OptionalLong.empty();
            }
        }
        return matched < 0 ? OptionalLong.empty() : OptionalLong.of(matched);
    }
}
