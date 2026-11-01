package com.qualityos.subscription.dto;

import com.qualityos.subscription.entity.FeatureDefinition;
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
            int displayOrder,
            Integer maxStorageMb,
            Integer maxMonthlyInspections,
            Integer maxAttachments,
            Map<String, Object> featureValues) {
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
            int trialDays,
            List<String> featureNames) {
    }

    public record SavePlanRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 300) String description,
            @PositiveOrZero BigDecimal monthlyPrice,
            @PositiveOrZero BigDecimal annualPrice,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal taxPercent,
            @Positive Integer maxUsers,
            @Positive Integer maxFacilities,
            @Positive Integer maxStorageMb,
            @Positive Integer maxMonthlyInspections,
            @Positive Integer maxAttachments,
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
            UUID tenantId, String name, UUID subscriptionId, String gstin, String contactEmail, String planCode, String planName,
            String state, String billingCycle, Instant endsAt, Instant cancelledAt, Instant suspendedAt,
            Instant registeredAt, long openInvoices, LocalDate nextDue) {
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
            Instant suspendedAt,
            String suspensionReason,
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
    public record CreateFeatureRequest(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,29}", message = "Use capital letters, digits and underscores.")
            String code,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 300) String description,
            @NotNull FeatureDefinition.ValueType valueType,
            boolean defaultEnabled) {
    }

    public record UpdateFeatureRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 300) String description,
            boolean defaultEnabled,
            @NotNull Long version) {
    }

    public record FeatureActiveRequest(
            boolean active, @NotNull Long version, @NotBlank @Size(max = 500) String reason) {
    }

    /** {@code reason} is needed once a plan has subscribers, as for any other change to it. */
    public record AssignFeatureRequest(
            @NotNull UUID planId, boolean included, Object value, @NotNull Long version, @Size(max = 500) String reason) {
    }

    /** {@code enforced} says whether leaving this out of a plan actually restricts anything. */
    public record FeatureResponse(
            String code, String name, String description, FeatureDefinition.ValueType valueType, boolean active,
            boolean defaultEnabled, boolean enforced, long organisations, long version) {
    }

    public record FeatureOrganisation(UUID tenantId, String name, String planName) {
    }

    public record FeatureOrganisationList(int total, List<FeatureOrganisation> items) {
    }

    public record UsageRow(
            UUID tenantId, String name, String planName, List<String> features, long users, Integer maxUsers,
            long facilities, Integer maxFacilities, long storageBytes, Integer maxStorageMb, long inspections,
            Integer maxMonthlyInspections, long attachments, Integer maxAttachments, String level, String upgradeTo) {
    }

    public record UsageList(int total, List<UsageRow> items) {
    }

    public record UsageHistory(
            LocalDate takenOn, long users, long facilities, long storageBytes, long inspections, long attachments) {
    }

    public record RevenueByPlan(String name, BigDecimal mrr) {
    }

    /** docs/PLATFORM_ADMIN.md defines each figure. */
    public record PlatformAnalytics(
            LocalDate from, LocalDate to, BigDecimal mrr, BigDecimal arr, BigDecimal openingMrr, long openingCustomers,
            long closingCustomers, long newCustomers, long registered, long converted, BigDecimal trialConversion,
            long lostCustomers, BigDecimal customerChurn, BigDecimal revenueLost, BigDecimal revenueChurn,
            BigDecimal averageRevenue, List<RevenueByPlan> revenueByPlan, List<PlanShare> plans, BigDecimal outstanding,
            BigDecimal collected, BigDecimal collectionRate, List<DashboardMonth> months) {
    }

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
            List<String> features,
            long storageBytes,
            Integer maxStorageMb,
            long inspectionsThisMonth,
            Integer maxMonthlyInspections,
            long attachments,
            Integer maxAttachments) {
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
            Instant paidAt,
            String taxTreatment,
            String placeOfSupply,
            BigDecimal cgstAmount,
            BigDecimal sgstAmount,
            BigDecimal igstAmount,
            String sacCode,
            String sellerGstin,
            String buyerName,
            String buyerGstin,
            Instant periodStartsAt,
            Instant periodEndsAt) {
    }

    public record PaymentResponse(
            UUID id,
            UUID invoiceId,
            String provider,
            String providerReference,
            BigDecimal amount,
            String status,
            String failureReason,
            Instant occurredAt,
            String method,
            LocalDate paidOn) {
    }

    public enum PaymentMethod { BANK_TRANSFER, UPI, NEFT, RTGS, IMPS, CHEQUE, OTHER }

    public record RecordPaymentRequest(
            @NotBlank @Size(max = 100) String reference,
            @NotNull @PositiveOrZero BigDecimal amount,
            boolean failed,
            @Size(max = 300) String failureReason,
            @NotNull PaymentMethod method,
            @NotNull LocalDate paidOn,
            @Size(max = 500) String notes) {
    }

    public record PaymentGateway(boolean available, String mode) {
    }

    /** The phone is sent to the gateway, which requires one, and is not stored here. */
    public record CheckoutRequest(@NotBlank @Pattern(regexp = "[6-9]\\d{9}", message = "Enter a 10-digit mobile number") String phone) {

        @Override
        public String toString() {
            return "CheckoutRequest[]";
        }
    }

    public record CheckoutResponse(String orderId, String paymentSessionId, String mode) {

        @Override
        public String toString() {
            return "CheckoutResponse[" + orderId + "]";
        }
    }

    public record OrderConfirmation(String status) {
    }

    /** Every operator action on an organisation says why. */
    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record InvoiceFilter(
            String q, String status, boolean overdue, LocalDate from, LocalDate to, BigDecimal min, BigDecimal max) {
    }

    public record PlatformInvoice(
            UUID id, UUID tenantId, String organisation, String invoiceNumber, String planName, String billingCycle,
            Instant periodStartsAt, Instant periodEndsAt, BigDecimal amount, BigDecimal taxAmount, BigDecimal total,
            BigDecimal paid, BigDecimal outstanding, String status, LocalDate issuedOn, LocalDate dueOn) {
    }

    public record InvoiceList(int total, List<PlatformInvoice> items) {
    }

    public record PlatformPayment(
            UUID id, UUID tenantId, String organisation, UUID invoiceId, String invoiceNumber, String provider,
            String reference, String method, BigDecimal amount, String status, String failureReason, LocalDate paidOn,
            String notes, String recordedBy, Instant recordedAt) {
    }

    public record PaymentList(int total, List<PlatformPayment> items) {
    }

    public record TrialSummary(long endingIn3Days, long endingIn7Days, long convertedThisMonth, BigDecimal conversionRate) {
    }

    /** For a plan with no list price, such as Enterprise. */
    public record CustomInvoiceRequest(
            @NotNull UUID planId, @NotNull Subscription.Cycle cycle, @PositiveOrZero BigDecimal amount,
            @NotBlank @Size(max = 500) String reason) {
    }

    /** @param total every organisation matching the filter, of which items holds this page */
    public record OrganisationList(int total, List<TenantSubscription> items) {
    }

    public record ExtendRequest(@Min(1) @Max(365) int days, @NotBlank @Size(max = 500) String reason) {
    }

    public record TenantSubscription(UUID tenantId, String organizationName, SubscriptionResponse subscription,
            List<InvoiceResponse> openInvoices, String gstin, String contactEmail, Instant registeredAt,
            UUID subscriptionId, Instant suspendedAt, LocalDate nextDue) {
    }
}
