package com.qualityos.identity.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param emailVerificationRequired new accounts must confirm their email address
 * @param verificationTokenExpiryMinutes how long a confirmation link works
 * @param allowUnverifiedLogin let a new account in while it is still unconfirmed, with a reminder
 */
@ConfigurationProperties("app.registration")
public record RegistrationProperties(
        boolean emailVerificationRequired,
        int verificationTokenExpiryMinutes,
        boolean allowUnverifiedLogin) {

    /** True when a new account can do nothing until its email address is confirmed. */
    public boolean blocksUnverified() {
        return emailVerificationRequired && !allowUnverifiedLogin;
    }

    Duration verificationTtl() {
        return Duration.ofMinutes(verificationTokenExpiryMinutes);
    }
}
