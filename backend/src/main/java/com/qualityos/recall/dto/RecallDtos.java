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

    public record ContactStatusRequest(@NotNull RecallContact.Status status) {
    }

    public record ExceptionRequest(@NotBlank @Size(max = 2000) String reason) {
    }

    /** @param acceptOutstanding the requester has seen that goods remain out, and says why in the note */
    public record RequestClosureRequest(@NotBlank @Size(max = 2000) String note, Boolean acceptOutstanding) {

        public boolean accepted() {
            return Boolean.TRUE.equals(acceptOutstanding);
        }
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
            List<String> blockers) {
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
            String closureRequestedBy,
            Instant closureRequestedAt,
            String closedBy,
            Instant closedAt,
            ClosureReview review) {
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
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal outstandingQuantity) {
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
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal outstandingQuantity) {
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
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal outstandingQuantity) {

        public String statusLabel() {
            return words(status);
        }

        public String quantityLabel() {
            return quantity.stripTrailingZeros().toPlainString();
        }

        public String returnedLabel() {
            return returnedQuantity.stripTrailingZeros().toPlainString();
        }

        public String outstandingLabel() {
            return outstandingQuantity.stripTrailingZeros().toPlainString();
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
            List<HistoryEntry> history) {
    }
}
