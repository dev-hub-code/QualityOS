package com.qualityos.subscription.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * @param keyId Razorpay's key id. A {@code rzp_test_} key is test mode, where no real money moves
 * @param keySecret the key's secret, which signs nothing sent to a browser and never leaves the server
 * @param webhookSecret the secret set on the webhook in Razorpay's dashboard. Without it, webhooks are refused, and
 *     payments are still confirmed when the customer returns and by the refunds job
 * @param baseUrl overrides Razorpay's API address, for tests
 */
@ConfigurationProperties("qualityos.payments.razorpay")
public record RazorpayProperties(String keyId, String keySecret, String webhookSecret, String baseUrl) {

    public boolean configured() {
        return StringUtils.hasText(keyId) && StringUtils.hasText(keySecret);
    }

    public boolean webhooksConfigured() {
        return configured() && StringUtils.hasText(webhookSecret);
    }

    /** Test keys say so in their name. */
    public String mode() {
        return keyId != null && keyId.startsWith("rzp_live_") ? "production" : "sandbox";
    }

    public String apiUrl() {
        return StringUtils.hasText(baseUrl) ? baseUrl : "https://api.razorpay.com/v1";
    }

    @Override
    public String toString() {
        return "RazorpayProperties[mode=" + mode() + "]";
    }
}
