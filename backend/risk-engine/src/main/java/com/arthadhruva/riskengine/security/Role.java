package com.arthadhruva.riskengine.security;

public enum Role {
    ANALYST, ADMIN, CLIENT, PLATFORM_ADMIN;

    /** ANALYST/ADMIN are internal staff with broad access to scoring/analysis tools -- TOTP is
     * mandatory for them, verified atomically (see AuthController). CLIENT is view-only and can
     * opt in via the two-step handshake instead. */
    public boolean requiresTotp() {
        return this == ADMIN || this == ANALYST || this == PLATFORM_ADMIN;
    }
}
