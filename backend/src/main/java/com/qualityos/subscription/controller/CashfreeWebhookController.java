package com.qualityos.subscription.controller;

import com.qualityos.subscription.service.CashfreePayments;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, because Cashfree holds no session. The signature on each request stands in for one. */
@RestController
@RequestMapping("/api/v1/public/webhooks/cashfree")
@Tag(name = "Subscription")
public class CashfreeWebhookController {

    private final CashfreePayments payments;

    CashfreeWebhookController(CashfreePayments payments) {
        this.payments = payments;
    }

    /** Raw bytes on purpose: the signature covers them exactly as sent. */
    @PostMapping
    public void receive(
            @RequestBody byte[] body,
            @RequestHeader(name = "x-webhook-timestamp", required = false) String timestamp,
            @RequestHeader(name = "x-webhook-signature", required = false) String signature) {
        payments.receiveWebhook(body, timestamp, signature);
    }
}
