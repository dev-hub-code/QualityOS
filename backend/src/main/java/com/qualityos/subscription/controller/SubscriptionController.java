package com.qualityos.subscription.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.subscription.dto.SubscriptionDtos.ChangePlanRequest;
import com.qualityos.subscription.dto.SubscriptionDtos.InvoiceResponse;
import com.qualityos.subscription.dto.SubscriptionDtos.PaymentResponse;
import com.qualityos.subscription.dto.SubscriptionDtos.PlanResponse;
import com.qualityos.subscription.dto.SubscriptionDtos.SubscriptionResponse;
import com.qualityos.subscription.entity.SubscriptionInvoice;
import com.qualityos.subscription.service.InvoicePdfRenderer;
import com.qualityos.subscription.service.SubscriptionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/subscription")
@Tag(name = "Subscription")
@PreAuthorize(Require.SUBSCRIPTION_MANAGE)
public class SubscriptionController {

    private final SubscriptionService subscriptions;
    private final InvoicePdfRenderer invoices;

    SubscriptionController(SubscriptionService subscriptions, InvoicePdfRenderer invoices) {
        this.subscriptions = subscriptions;
        this.invoices = invoices;
    }

    /** Any member may see where the organisation stands, so that the app can warn before it turns read-only. */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<SubscriptionResponse> current() {
        return ApiResponse.ok(subscriptions.current());
    }

    @GetMapping("/plans")
    public ApiResponse<List<PlanResponse>> plans() {
        return ApiResponse.ok(subscriptions.activePlans());
    }

    @PostMapping("/plan")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InvoiceResponse> requestPlan(@Valid @RequestBody ChangePlanRequest request) {
        return ApiResponse.ok(subscriptions.requestPlan(request));
    }

    @PostMapping("/cancel")
    public ApiResponse<SubscriptionResponse> cancel() {
        return ApiResponse.ok(subscriptions.cancel());
    }

    @PostMapping("/resume")
    public ApiResponse<SubscriptionResponse> resume() {
        return ApiResponse.ok(subscriptions.resume());
    }

    @GetMapping("/invoices")
    public ApiResponse<List<InvoiceResponse>> invoices() {
        return ApiResponse.ok(subscriptions.invoices());
    }

    @GetMapping("/payments")
    public ApiResponse<List<PaymentResponse>> payments() {
        return ApiResponse.ok(subscriptions.payments());
    }

    @GetMapping("/invoices/{id}/pdf")
    public ResponseEntity<byte[]> invoicePdf(@PathVariable UUID id) {
        SubscriptionInvoice invoice = subscriptions.invoice(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(invoice.getInvoiceNumber() + ".pdf").build().toString())
                .body(invoices.render(invoice));
    }
}
