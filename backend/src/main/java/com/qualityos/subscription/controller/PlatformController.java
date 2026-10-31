package com.qualityos.subscription.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.subscription.dto.SubscriptionDtos.CustomInvoiceRequest;
import com.qualityos.subscription.dto.SubscriptionDtos.ExtendRequest;
import com.qualityos.subscription.dto.SubscriptionDtos.InvoiceResponse;
import com.qualityos.subscription.dto.SubscriptionDtos.OrganisationList;
import com.qualityos.subscription.dto.SubscriptionDtos.PaymentResponse;
import com.qualityos.subscription.dto.SubscriptionDtos.PlanResponse;
import com.qualityos.subscription.dto.SubscriptionDtos.RecordPaymentRequest;
import com.qualityos.subscription.dto.SubscriptionDtos.SavePlanRequest;
import com.qualityos.subscription.dto.SubscriptionDtos.SubscriptionResponse;
import com.qualityos.subscription.service.PlatformService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A tenant id in this path is an operator's choice of organisation, open only to platform administrators. */
@RestController
@RequestMapping("/api/v1/platform")
@Tag(name = "Platform administration")
@PreAuthorize(Require.PLATFORM_ADMIN)
public class PlatformController {

    private final PlatformService platform;

    PlatformController(PlatformService platform) {
        this.platform = platform;
    }

    @GetMapping("/plans")
    public ApiResponse<List<PlanResponse>> plans() {
        return ApiResponse.ok(platform.plans());
    }

    @PutMapping("/plans/{id}")
    public ApiResponse<PlanResponse> savePlan(@PathVariable UUID id, @Valid @RequestBody SavePlanRequest request) {
        return ApiResponse.ok(platform.savePlan(id, request));
    }

    @GetMapping("/organisations")
    public ApiResponse<OrganisationList> organisations() {
        return ApiResponse.ok(platform.organisations());
    }

    @PostMapping("/organisations/{tenantId}/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InvoiceResponse> raiseInvoice(
            @PathVariable UUID tenantId, @Valid @RequestBody CustomInvoiceRequest request) {
        return ApiResponse.ok(platform.raiseInvoice(tenantId, request));
    }

    @PostMapping("/organisations/{tenantId}/invoices/{invoiceId}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PaymentResponse> recordPayment(
            @PathVariable UUID tenantId, @PathVariable UUID invoiceId, @Valid @RequestBody RecordPaymentRequest request) {
        return ApiResponse.ok(platform.recordPayment(tenantId, invoiceId, request));
    }

    @PostMapping("/organisations/{tenantId}/extend")
    public ApiResponse<SubscriptionResponse> extend(@PathVariable UUID tenantId, @Valid @RequestBody ExtendRequest request) {
        return ApiResponse.ok(platform.extend(tenantId, request.days()));
    }
}
