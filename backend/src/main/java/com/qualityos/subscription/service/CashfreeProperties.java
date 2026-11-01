package com.qualityos.subscription.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * @param environment {@code sandbox} or {@code production}
 * @param baseUrl overrides the address that follows from the environment
 */
@ConfigurationProperties("qualityos.payments.cashfree")
public record CashfreeProperties(String clientId, String clientSecret, String environment, String baseUrl) {

    public boolean configured() {
        return StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret);
    }

    public String mode() {
        return "production".equals(environment) ? "production" : "sandbox";
    }

    public String apiUrl() {
        if (StringUtils.hasText(baseUrl)) {
            return baseUrl;
        }
        return "production".equals(environment) ? "https://api.cashfree.com/pg" : "https://sandbox.cashfree.com/pg";
    }

    @Override
    public String toString() {
        return "CashfreeProperties[environment=" + environment + "]";
    }
}
