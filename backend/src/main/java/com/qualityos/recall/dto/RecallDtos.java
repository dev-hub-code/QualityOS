package com.qualityos.recall.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.qualityos.inspection.dto.InspectionDtos.InspectionHistory;
import com.qualityos.quality.dto.QualityDtos.NcrHistory;
import com.qualityos.recall.entity.RecallContact;
import com.qualityos.recall.entity.RecallContactAttempt;
import com.qualityos.traceability.dto.TraceabilityDtos.RecallLine;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class RecallDtos {

    private RecallDtos() {
    }

    public record CreateRecallRequest(
            @NotEmpty @Size(max = 200) List<@NotBlank @Size(max = 100) String> batchNumbers,
            @NotNull LocalDate recallDate,
            @NotBlank @Size(max = 2000) String reason,
            @NotBlank @Size(max = 4000) String instructions,
            UUID capaId) {
    }

    /** More batches found after a recall started, with why they belong to it. */
    public record AddBatchesRequest(
            @NotEmpty @Size(max = 200) List<@NotBlank @Size(max = 100) String> batchNumbers,
            @NotBlank @Size(max = 2000) String reason) {
    }

    /** @param capaId empty to unlink the CAPA */
    public record LinkCapaRequest(UUID capaId) {
    }

    public record UpdateContactRequest(
            @Size(max = 200) String contactPerson,
            @Size(max = 500) String contactDetails,
            UUID assignedTo,
            LocalDate followUpOn,
            @Size(max = 2000) String notes) {
    }

    public record ContactAttemptRequest(
            @NotNull RecallContactAttempt.Method method,
            @Size(max = 200) String contactPerson,
            @Size(max = 500) String contactDetails,
            @NotNull Instant attemptedAt,
            @NotNull RecallContactAttempt.Outcome outcome,
            @Size(max = 2000) String customerResponse,
            @Size(max = 2000) String notes,
            LocalDate followUpOn) {
    }

    public record AcknowledgementRequest(
            @NotNull UUID customerId,
            @NotNull LocalDate acknowledgedOn,
            @NotBlank @Size(max = 200) String acknowledgedBy,
            @Size(max = 2000) String customerResponse) {
    }

    public record ContactStatusRequest(
            @NotNull RecallContact.Status status,
            /* Needed to resolve a customer who still holds recalled units: why that is acceptable. */
            @Size(max = 2000) String note) {
    }

    public record ExceptionRequest(@NotBlank @Size(max = 2000) String reason) {
    }

    /** @param acceptOutstanding the requester has seen that goods remain out, and says why in the note */
    public record RequestClosureRequest(@NotBlank @Size(max = 2000) String note, Boolean acceptOutstanding) {

        public boolean accepted() {
            return Boolean.TRUE.equals(acceptOutstanding);
        }
    }

    /** The note is needed only when someone approves their own request in a one-person organisation. */
    public record CloseRequest(@Size(max = 2000) String note) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 2000) String reason) {
    }

    public record RecallSummary(
            UUID id, String recallNumber, LocalDate recallDate, String reason, String status, List<String> batchNumbers) {
    }

    /** What a closer reviews. Any {@code blockers} refuse closure. */
    public record ClosureReview(
            int customers,
            int contacted,
            int acknowledged,
            int exceptions,
            List<String> unresolvedCustomers,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal dispatchedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal returnedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal outstandingQuantity,
            String capaStatus,
            List<String> blockers,
            Totals totals) {
    }

    /**
     * Quantities as a person reads them, by unit: {@code 100.5 M · 5 PCS}. The bare numbers beside them add units
     * together whenever a recall reaches products in more than one, so only these are shown.
     */
    public record Totals(String dispatched, String returned, String outstanding, boolean oneUnit) {

        public static Totals of(List<com.qualityos.traceability.dto.TraceabilityDtos.RecallLine> lines) {
            java.util.function.Function<com.qualityos.traceability.dto.TraceabilityDtos.RecallLine, String> unit =
                    com.qualityos.traceability.dto.TraceabilityDtos.RecallLine::unit;
            return new Totals(
                    com.qualityos.common.util.Quantities.total(lines, unit,
                            com.qualityos.traceability.dto.TraceabilityDtos.RecallLine::quantity),
                    com.qualityos.common.util.Quantities.total(lines, unit,
                            com.qualityos.traceability.dto.TraceabilityDtos.RecallLine::returnedQuantity),
                    com.qualityos.common.util.Quantities.total(lines, unit,
                            com.qualityos.traceability.dto.TraceabilityDtos.RecallLine::outstandingQuantity),
                    com.qualityos.common.util.Quantities.oneUnit(lines, unit));
        }
    }

    public record RecallDetail(
            UUID id,
            String recallNumber,
            LocalDate recallDate,
            String reason,
            String instructions,
            UUID capaId,
            String status,
            List<String> batchNumbers,
            String closureNote,
            UUID closureRequestedById,
            String closureRequestedBy,
            Instant closureRequestedAt,
            String closedBy,
            Instant closedAt,
            ClosureReview review,
            String hazardClass,
            String regulator,
            String regulatorReference,
            LocalDate regulatorNotifiedOn,
            int responseDays) {
    }

    /**
     * What a regulator is told about the recall. Every field is sent; blanks clear.
     *
     * @param responseDays days after the recall date that customers have to acknowledge it before its approvers hear
     */
    public record RegulatoryRequest(
            com.qualityos.recall.entity.Recall.HazardClass hazardClass,
            @jakarta.validation.constraints.Size(max = 100) String regulator,
            @jakarta.validation.constraints.Size(max = 100) String regulatorReference,
            LocalDate regulatorNotifiedOn,
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(60) int responseDays) {
    }

    /** The linked CAPA, as a regulator's report shows it. */
    public record ReportCapa(String number, String title, String status, String rootCause) {
    }

    /** The recall report, with what a regulator asks besides. @param capa null when none is linked */
    public record RegulatoryReport(
            RecallReport report,
            ReportCapa capa,
            boolean finalReport,
            int reached,
            int acknowledged,
            int resolved,
            int unreachable,
            int exceptions,
            String reachedPercent,
            String acknowledgedPercent,
            String recoveredPercent,
            List<ReportCustomer> unanswered) {
    }

    public record ContactView(
            UUID id,
            UUID customerId,
            String customerCode,
            String customerName,
            String status,
            String contactPerson,
            String contactDetails,
            UUID assignedTo,
            String assignedName,
            LocalDate followUpOn,
            String notes,
            String exceptionReason,
            long attempts,
            Instant lastAttemptAt,
            LocalDate acknowledgedOn,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal returnedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal outstandingQuantity,
            Totals totals) {
    }

    public record HistoryEntry(
            UUID id,
            String kind,
            UUID customerId,
            String customerName,
            String method,
            String contactPerson,
            String contactDetails,
            Instant at,
            LocalDate on,
            String outcome,
            String customerResponse,
            String notes,
            String recordedBy) {

        @JsonProperty
        public String kindLabel() {
            return method == null ? "Acknowledgement" : words(method).replace("Whatsapp", "WhatsApp");
        }

        @JsonProperty
        public String outcomeLabel() {
            return outcome == null ? "" : words(outcome);
        }
    }

    /** {@code RETURN_IN_PROGRESS} as a person would write it. */
    private static String words(String code) {
        String spaced = code.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    public record RecallDashboard(
            int totalCustomers,
            int pending,
            int contactAttempts,
            int contacted,
            int acknowledged,
            int unreachable,
            int returnsInProgress,
            int resolved,
            int outstandingFollowUps,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal dispatchedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal returnedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal outstandingQuantity,
            Totals totals) {
    }

    public record ReportProduct(String code, String name, String batchNumber, String status) {
    }

    public record ReportCustomer(
            String code,
            String name,
            String contactPerson,
            String email,
            String phone,
            String address,
            String status,
            String assignedName,
            long attempts,
            LocalDate acknowledgedOn,
            String exceptionReason,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal returnedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal outstandingQuantity,
            Totals totals) {

        public String statusLabel() {
            return words(status);
        }

        public String quantityLabel() {
            return totals.dispatched();
        }

        public String returnedLabel() {
            return totals.returned();
        }

        public String outstandingLabel() {
            return totals.outstanding();
        }
    }

    public record RecallReport(
            String organizationName,
            String organizationLegalName,
            String organizationAddress,
            String organizationGstin,
            Instant generatedAt,
            String generatedLabel,
            RecallDetail recall,
            List<ReportProduct> products,
            List<ReportCustomer> customers,
            List<RecallLine> dispatches,
            List<String> serialNumbers,
            List<InspectionHistory> inspections,
            List<NcrHistory> ncrs,
            List<HistoryEntry> history,
            /* When it closed, in the organisation's time zone, as the report prints it. Null while open. */
            String closedLabel) {
    }
}
