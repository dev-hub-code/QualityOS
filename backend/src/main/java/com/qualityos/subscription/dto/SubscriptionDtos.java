package com.qualityos.subscription.dto;

import com.qualityos.subscription.entity.Subscription;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class SubscriptionDtos {

    private SubscriptionDtos() {
    }

    public record PlanResponse(
            UUID id,
            String code,
            String name,
            String description,
            BigDecimal monthlyPrice,
            BigDecimal annualPrice,
            BigDecimal taxPercent,
            Integer maxUsers,
            Integer maxFacilities,
            List<String> features,
            int trialDays,
            int graceDays,
            boolean active,
            long version,
            String currency,
            boolean publicVisible,
            Instant archivedAt,
            int displayOrder) {
    }

    public record PublicPlan(
            String code,
            String name,
            String description,
            BigDecimal monthlyPrice,
            BigDecimal annualPrice,
            Integer maxUsers,
            Integer maxFacilities,
            List<String> features,
            int trialDays) {
    }

    public record SavePlanRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 300) String description,
            @PositiveOrZero BigDecimal monthlyPrice,
            @PositiveOrZero BigDecimal annualPrice,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal taxPercent,
            @Positive Integer maxUsers,
            @Positive Integer maxFacilities,
            @NotNull Set<String> features,
            @Min(0) @Max(365) int trialDays,
            @Min(0) @Max(90) int graceDays,
            boolean active,
            @NotNull Long version,
            Boolean publicVisible,
            @Min(0) @Max(1000) Integer displayOrder,
            @Size(max = 500) String reason) {
    }

    public record CreatePlanRequest(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,29}", message = "Use capital letters, digits and underscores.")
            String code,
            @NotBlank @Size(max = 100) String name) {
    }

    public record PlanActionRequest(@NotNull Long version, @NotBlank @Size(max = 500) String reason) {
    }

    public record PlanActiveRequest(boolean active, @NotNull Long version, @NotBlank @Size(max = 500) String reason) {
    }

    /** What a change to this plan would reach. */
    public record PlanImpact(long subscribers) {
    }

    public record PlanVersion(long version, Map<String, Object> snapshot, String reason, UUID changedBy, Instant changedAt) {
    }

    public record OrganisationFilter(
            String q, String plan, String state, boolean unpaid, LocalDate registeredFrom, LocalDate registeredTo,
            String sort) {
    }

    public record OrganisationRow(
            UUID tenantId, String name, String gstin, String contactEmail, String planName, String state,
            Instant endsAt, Instant registeredAt, long openInvoices) {
    }

    public record OrganisationUsage(long inspections, long ncrs, long capas, long attachments, long attachmentBytes) {
    }

    public record OrganisationActivity(String action, String entityType, Instant occurredAt) {
    }

    public record OrganisationDetail(
            UUID tenantId,
            String name,
            String legalName,
            String gstin,
            String contactEmail,
            String countryCode,
            String timezone,
            Instant registeredAt,
            SubscriptionResponse subscription,
            OrganisationUsage usage,
            List<InvoiceResponse> invoices,
            List<PaymentResponse> payments,
            List<OrganisationActivity> activity) {
    }

    public record PlanShare(String name, long count) {
    }

    public record DashboardMonth(
            String month, BigDecimal mrr, long paying, long registered, long converted, BigDecimal collected,
            long cancelled) {
    }

    /**
     * Figures for a period, defined in docs/PLATFORM_ADMIN.md. A rate is null when there is nothing to measure.
     *
     * @param mrr contracted recurring revenue today, without tax. {@code collected} is money actually received
     */
    public record PlatformDashboard(
            LocalDate from,
            LocalDate to,
            long organisations,
            long active,
            long trials,
            long grace,
            long readOnly,
            long expired,
            BigDecimal mrr,
            BigDecimal arr,
            BigDecimal outstanding,
            long outstandingInvoices,
            BigDecimal collected,
            BigDecimal collectionRate,
            BigDecimal churnRate,
            List<PlanShare> plans,
            List<DashboardMonth> months) {
    }

    /** @param state what the dates mean today: TRIAL, ACTIVE, GRACE or READ_ONLY */
    public record SubscriptionResponse(
            String planCode,
            String planName,
            String state,
            Subscription.Cycle billingCycle,
            Instant endsAt,
            Instant readOnlyFrom,
            Instant cancelledAt,
            long users,
            Integer maxUsers,
            long facilities,
            Integer maxFacilities,
            List<String> features) {
    }

    public record ChangePlanRequest(@NotNull UUID planId, @NotNull Subscription.Cycle cycle) {
    }

    public record InvoiceResponse(
            UUID id,
            String invoiceNumber,
            String planName,
            Subscription.Cycle billingCycle,
            BigDecimal amount,
            BigDecimal taxPercent,
            BigDecimal taxAmount,
            BigDecimal total,
            String status,
            LocalDate issuedOn,
            LocalDate dueOn,
            Instant paidAt) {
    }

    public record PaymentResponse(
            UUID id,
            UUID invoiceId,
            String provider,
            String providerReference,
            BigDecimal amount,
            String status,
            String failureReason,
            Instant occurredAt) {
    }

    public record RecordPaymentRequest(
            @NotBlank @Size(max = 100) String reference,
            @NotNull @PositiveOrZero BigDecimal amount,
            boolean failed,
            @Size(max = 300) String failureReason) {
    }

    /** For a plan with no list price, such as Enterprise. */
    public record CustomInvoiceRequest(
            @NotNull UUID planId, @NotNull Subscription.Cycle cycle, @NotNull @PositiveOrZero BigDecimal amount) {
    }

    /** @param total every organisation matching the filter, of which items holds this page */
    public record OrganisationList(int total, List<TenantSubscription> items) {
    }

    public record ExtendRequest(@Min(1) @Max(365) int days) {
    }

    public record TenantSubscription(UUID tenantId, String organizationName, SubscriptionResponse subscription,
            List<InvoiceResponse> openInvoices, String gstin, String contactEmail, Instant registeredAt) {
    }
}
