package com.qualityos.subscription.controller;

import com.qualityos.subscription.service.RazorpayPayments;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, because Razorpay holds no session. The signature on each request stands in for one. */
@RestController
@RequestMapping("/api/v1/public/webhooks/razorpay")
@Tag(name = "Subscription")
public class RazorpayWebhookController {

    private final RazorpayPayments payments;

    RazorpayWebhookController(RazorpayPayments payments) {
        this.payments = payments;
    }

    /** Raw bytes on purpose: the signature covers them exactly as sent. */
    @PostMapping
    public void receive(
            @RequestBody byte[] body,
            @RequestHeader(name = "x-razorpay-signature", required = false) String signature) {
        payments.receiveWebhook(body, signature);
    }
}
