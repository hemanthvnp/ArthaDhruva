package com.arthadhruva.riskengine.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class PasswordHashScratch2 {
    @Test
    void printHash() {
        System.out.println("HASH:" + new BCryptPasswordEncoder().encode("AdminPass123!"));
    }
}
