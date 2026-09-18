package com.arthadhruva.riskengine.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for TOTP secrets at rest -- unlike a password, a TOTP secret must stay
 * recoverable to verify future codes, so it can't be one-way hashed like {@code passwordHash}.
 * Same "sensible local default, real env var for anything that matters" pattern as
 * {@link JwtService}: if {@code TOTP_ENCRYPTION_KEY} isn't configured, a random key is generated
 * at startup and logged as a warning -- every enrolled account's secret becomes undecryptable on
 * the next restart, forcing re-enrollment (recoverable via the admin reset-2fa endpoint, the same
 * severity as JWT_SECRET rotating and forcing everyone to re-login).
 */
@Component
public class TotpSecretCipher {

    private static final Logger log = LoggerFactory.getLogger(TotpSecretCipher.class);
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final SecretKey key;
    private final SecureRandom secureRandom = new SecureRandom();

    public TotpSecretCipher(@Value("${totp.encryption-key}") String configuredKey) {
        if (configuredKey == null || configuredKey.isBlank()) {
            try {
                KeyGenerator generator = KeyGenerator.getInstance("AES");
                generator.init(256);
                this.key = generator.generateKey();
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("Failed to generate a TOTP encryption key", e);
            }
            log.warn("TOTP_ENCRYPTION_KEY not set -- generated a random encryption key for this run. "
                    + "Every enrolled 2FA secret becomes undecryptable on the next restart, forcing "
                    + "re-enrollment. Set TOTP_ENCRYPTION_KEY (base64-encoded 32 bytes, e.g. "
                    + "`openssl rand -base64 32`) for a real deployment.");
        } else {
            byte[] decoded = Base64.getDecoder().decode(configuredKey);
            this.key = new SecretKeySpec(decoded, "AES");
        }
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv).put(ciphertext);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt TOTP secret", e);
        }
    }

    public String decrypt(String stored) {
        try {
            byte[] combined = Base64.getDecoder().decode(stored);
            ByteBuffer buffer = ByteBuffer.wrap(combined);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to decrypt TOTP secret", e);
        }
    }
}
