package com.qualityos.subscription.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.subscription.dto.SubscriptionDtos.CreatePlanRequest;
import com.qualityos.subscription.dto.SubscriptionDtos.CustomInvoiceRequest;
import com.qualityos.subscription.dto.SubscriptionDtos.ExtendRequest;
import com.qualityos.subscription.dto.SubscriptionDtos.InvoiceFilter;
import com.qualityos.subscription.dto.SubscriptionDtos.InvoiceList;
import com.qualityos.subscription.dto.SubscriptionDtos.InvoiceResponse;
import com.qualityos.subscription.dto.SubscriptionDtos.OrganisationDetail;
import com.qualityos.subscription.dto.SubscriptionDtos.OrganisationFilter;
import com.qualityos.subscription.dto.SubscriptionDtos.OrganisationList;
import com.qualityos.subscription.dto.SubscriptionDtos.PaymentList;
import com.qualityos.subscription.dto.SubscriptionDtos.PaymentResponse;
import com.qualityos.subscription.dto.SubscriptionDtos.PlanActionRequest;
import com.qualityos.subscription.dto.SubscriptionDtos.PlanActiveRequest;
import com.qualityos.subscription.dto.SubscriptionDtos.PlanImpact;
import com.qualityos.subscription.dto.SubscriptionDtos.PlanResponse;
import com.qualityos.subscription.dto.SubscriptionDtos.PlanVersion;
import com.qualityos.subscription.dto.SubscriptionDtos.PlatformDashboard;
import com.qualityos.subscription.dto.SubscriptionDtos.ReasonRequest;
import com.qualityos.subscription.dto.SubscriptionDtos.RecordPaymentRequest;
import com.qualityos.subscription.dto.SubscriptionDtos.SavePlanRequest;
import com.qualityos.subscription.dto.SubscriptionDtos.SubscriptionResponse;
import com.qualityos.subscription.dto.SubscriptionDtos.TrialSummary;
import com.qualityos.subscription.entity.SubscriptionInvoice;
import com.qualityos.subscription.service.InvoicePdfRenderer;
import com.qualityos.subscription.service.PlanCatalogue;
import com.qualityos.subscription.service.PlatformInsights;
import com.qualityos.subscription.service.PlatformService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

/** A tenant id in this path is an operator's choice of organisation, open only to platform administrators. */
@RestController
@RequestMapping("/api/v1/platform")
@Tag(name = "Platform administration")
@PreAuthorize(Require.PLATFORM_ADMIN)
@Validated
public class PlatformController {

    private final PlatformService platform;
    private final PlatformInsights insights;
    private final PlanCatalogue catalogue;
    private final InvoicePdfRenderer renderer;

    PlatformController(
            PlatformService platform, PlatformInsights insights, PlanCatalogue catalogue, InvoicePdfRenderer renderer) {
        this.platform = platform;
        this.insights = insights;
        this.catalogue = catalogue;
        this.renderer = renderer;
    }

    @GetMapping("/plans")
    public ApiResponse<List<PlanResponse>> plans() {
        return ApiResponse.ok(platform.plans());
    }

    @PutMapping("/plans/{id}")
    public ApiResponse<PlanResponse> savePlan(@PathVariable UUID id, @Valid @RequestBody SavePlanRequest request) {
        return ApiResponse.ok(platform.savePlan(id, request));
    }

    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PlanResponse> createPlan(@Valid @RequestBody CreatePlanRequest request) {
        return ApiResponse.ok(catalogue.create(request));
    }

    @PostMapping("/plans/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PlanResponse> duplicatePlan(@PathVariable UUID id, @Valid @RequestBody CreatePlanRequest request) {
        return ApiResponse.ok(catalogue.duplicate(id, request));
    }

    @PostMapping("/plans/{id}/active")
    public ApiResponse<PlanResponse> setPlanActive(@PathVariable UUID id, @Valid @RequestBody PlanActiveRequest request) {
        return ApiResponse.ok(catalogue.setActive(id, request));
    }

    @PostMapping("/plans/{id}/archive")
    public ApiResponse<PlanResponse> archivePlan(@PathVariable UUID id, @Valid @RequestBody PlanActionRequest request) {
        return ApiResponse.ok(catalogue.archive(id, request));
    }

    @GetMapping("/plans/{id}/impact")
    public ApiResponse<PlanImpact> planImpact(@PathVariable UUID id) {
        return ApiResponse.ok(catalogue.impact(id));
    }

    @GetMapping("/plans/{id}/versions")
    public ApiResponse<List<PlanVersion>> planVersions(@PathVariable UUID id) {
        return ApiResponse.ok(catalogue.versions(id));
    }

    @GetMapping("/dashboard")
    public ApiResponse<PlatformDashboard> dashboard(
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        return ApiResponse.ok(insights.dashboard(from, to));
    }

    @GetMapping("/organisations")
    public ApiResponse<OrganisationList> organisations(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String plan,
            @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "false") boolean unpaid,
            @RequestParam(required = false) LocalDate registeredFrom,
            @RequestParam(required = false) LocalDate registeredTo,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(insights.organisations(
                new OrganisationFilter(q, plan, state, unpaid, registeredFrom, registeredTo, sort), page, size));
    }

    @GetMapping(value = "/organisations/export", produces = "text/csv")
    public ResponseEntity<String> exportOrganisations(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String plan,
            @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "false") boolean unpaid,
            @RequestParam(required = false) LocalDate registeredFrom,
            @RequestParam(required = false) LocalDate registeredTo,
            @RequestParam(required = false) String sort) {
        StringBuilder csv = new StringBuilder("Organisation,Email,GSTIN,Plan,State,Ends,Registered,Open invoices\n");
        insights.export(new OrganisationFilter(q, plan, state, unpaid, registeredFrom, registeredTo, sort))
                .forEach(row -> csv.append(Stream.of(row.name(), row.contactEmail(), row.gstin(), row.planName(),
                                row.state(), row.endsAt().toString(), row.registeredAt().toString(),
                                String.valueOf(row.openInvoices()))
                        .map(PlatformController::cell)
                        .collect(Collectors.joining(","))).append('\n'));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"organisations.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv.toString());
    }

    /** Quoted, and a leading formula character is neutralised, because names come from customers. */
    private static String cell(String value) {
        String text = value == null ? "" : value;
        String safe = text.matches("^[=+\\-@\\t\\r].*") ? "'" + text : text;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    @GetMapping("/organisations/{tenantId}")
    public ApiResponse<OrganisationDetail> organisation(@PathVariable UUID tenantId) {
        return ApiResponse.ok(insights.organisation(tenantId));
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
        return ApiResponse.ok(platform.extend(tenantId, request.days(), request.reason()));
    }

    @PostMapping("/organisations/{tenantId}/trial/end")
    public ApiResponse<SubscriptionResponse> endTrial(@PathVariable UUID tenantId, @Valid @RequestBody ReasonRequest request) {
        return ApiResponse.ok(platform.endTrial(tenantId, request.reason()));
    }

    @PostMapping("/organisations/{tenantId}/cancel")
    public ApiResponse<SubscriptionResponse> cancel(@PathVariable UUID tenantId, @Valid @RequestBody ReasonRequest request) {
        return ApiResponse.ok(platform.cancel(tenantId, request.reason()));
    }

    @PostMapping("/organisations/{tenantId}/resume")
    public ApiResponse<SubscriptionResponse> resume(@PathVariable UUID tenantId, @Valid @RequestBody ReasonRequest request) {
        return ApiResponse.ok(platform.resume(tenantId, request.reason()));
    }

    @PostMapping("/organisations/{tenantId}/suspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void suspend(@PathVariable UUID tenantId, @Valid @RequestBody ReasonRequest request) {
        platform.suspend(tenantId, request.reason());
    }

    @PostMapping("/organisations/{tenantId}/reactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reactivate(@PathVariable UUID tenantId, @Valid @RequestBody ReasonRequest request) {
        platform.reactivate(tenantId, request.reason());
    }

    @GetMapping("/organisations/{tenantId}/invoices/{invoiceId}")
    public ApiResponse<InvoiceResponse> invoice(@PathVariable UUID tenantId, @PathVariable UUID invoiceId) {
        return ApiResponse.ok(insights.invoiceDetail(tenantId, invoiceId));
    }

    @GetMapping("/organisations/{tenantId}/invoices/{invoiceId}/pdf")
    public ResponseEntity<byte[]> invoicePdf(@PathVariable UUID tenantId, @PathVariable UUID invoiceId) {
        SubscriptionInvoice invoice = insights.invoice(tenantId, invoiceId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + invoice.getInvoiceNumber() + ".pdf\"")
                .body(renderer.render(invoice));
    }

    @GetMapping("/invoices")
    public ApiResponse<InvoiceList> invoices(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") boolean overdue,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) BigDecimal min,
            @RequestParam(required = false) BigDecimal max,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(insights.invoices(new InvoiceFilter(q, status, overdue, from, to, min, max), page, size));
    }

    @GetMapping(value = "/invoices/export", produces = "text/csv")
    public ResponseEntity<String> exportInvoices(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") boolean overdue,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) BigDecimal min,
            @RequestParam(required = false) BigDecimal max) {
        StringBuilder csv = new StringBuilder(
                "Invoice,Organisation,Plan,Subtotal,GST,Total,Paid,Outstanding,Status,Issued,Due\n");
        insights.exportInvoices(new InvoiceFilter(q, status, overdue, from, to, min, max))
                .forEach(row -> csv.append(Stream.of(row.invoiceNumber(), row.organisation(), row.planName(),
                                row.amount().toPlainString(), row.taxAmount().toPlainString(),
                                row.total().toPlainString(), row.paid().toPlainString(),
                                row.outstanding().toPlainString(), row.status(), row.issuedOn().toString(),
                                row.dueOn().toString())
                        .map(PlatformController::cell)
                        .collect(Collectors.joining(","))).append('\n'));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"invoices.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv.toString());
    }

    @GetMapping("/payments")
    public ApiResponse<PaymentList> payments(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(insights.payments(q, status, from, to, page, size));
    }

    @GetMapping("/trials/summary")
    public ApiResponse<TrialSummary> trialSummary() {
        return ApiResponse.ok(insights.trialSummary());
    }
}
