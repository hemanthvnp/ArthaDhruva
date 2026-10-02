package com.arthadhruva.riskengine.security;

import org.springframework.stereotype.Component;

import java.util.OptionalLong;

/**
 * The single place a TOTP code is checked. A code is accepted only if it is valid for a time step
 * newer than the last step this account used; accepting it records that step atomically, so the same
 * code can never succeed twice (not even two concurrent requests racing with it).
 */
@Component
public class SecondFactor {

    private final TotpService totp;
    private final TotpSecretCipher cipher;
    private final UserService users;

    public SecondFactor(TotpService totp, TotpSecretCipher cipher, UserService users) {
        this.totp = totp;
        this.cipher = cipher;
        this.users = users;
    }

    public boolean verifyAndConsume(User user, String code) {
        if (user.getTotpSecret() == null) {
            return false;
        }
        String secret;
        try {
            secret = cipher.decrypt(user.getTotpSecret());
        } catch (IllegalStateException e) {
            // Undecryptable (key rotated since enrollment): the same outcome as a wrong code, not a 500.
            return false;
        }
        OptionalLong step = totp.matchingStep(secret, code);
        return step.isPresent() && users.claimTotpStep(user, step.getAsLong());
    }
}
