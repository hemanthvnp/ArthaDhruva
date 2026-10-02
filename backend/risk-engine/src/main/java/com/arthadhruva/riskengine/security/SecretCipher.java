package com.arthadhruva.riskengine.security;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for secrets that must stay recoverable at rest: SSO client secrets and webhook
 * signing secrets. The purpose string is bound as GCM associated data, so a ciphertext copied from one
 * column into another (a webhook secret into the SSO table, say) fails authentication instead of
 * decrypting. Uses the same data-encryption key as {@link TotpSecretCipher}.
 *
 * <p>Values carry an {@code enc:v1:} prefix. A value without it is a legacy plaintext row written before
 * encryption existed: it is returned as-is so nothing breaks, and the next save stores it encrypted.
 */
@Component
public class SecretCipher {

    public static final String SSO_CLIENT_SECRET = "sso-client-secret";
    public static final String WEBHOOK_SECRET = "webhook-signing-secret";

    private static final String PREFIX = "enc:v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final TotpSecretCipher keyHolder;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(TotpSecretCipher keyHolder) {
        this.keyHolder = keyHolder;
    }

    public String encrypt(String plaintext, String purpose) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keyHolder.key(), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(IV_LENGTH + ciphertext.length)
                    .put(iv).put(ciphertext).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt secret", e);
        }
    }

    /** @throws IllegalStateException if the value is encrypted but cannot be authenticated */
    public String decrypt(String stored, String purpose) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (combined.length <= IV_LENGTH) {
                throw new IllegalStateException("Encrypted secret is truncated");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keyHolder.key(), new GCMParameterSpec(TAG_BITS, combined, 0, IV_LENGTH));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(combined, IV_LENGTH, combined.length - IV_LENGTH), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Failed to decrypt secret (" + purpose + ")", e);
        }
    }

    public boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }
}
