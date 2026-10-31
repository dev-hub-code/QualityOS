package com.qualityos.quality.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.qualityos.quality.entity.CapaAction;
import com.qualityos.quality.entity.NonConformance;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class QualityDtos {

    private static final int MAX_TEXT = 4000;
    private static final int MAX_WHYS = 10;
    private static final int MAX_BRANCHES = 12;

    private QualityDtos() {
    }

    public record Assignee(UUID id, String fullName) {
    }

    public record SaveDefectCategoryRequest(
            @NotBlank @Size(max = 50) String code, @NotBlank @Size(max = 200) String name) {
    }

    public record DefectCategoryResponse(UUID id, String code, String name) {
    }

    public record CreateNcrRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = MAX_TEXT) String description,
            @NotNull NonConformance.Severity severity,
            UUID defectCategoryId,
            UUID supplierId,
            UUID materialId,
            @Size(max = 100) String batchNumber,
            @PositiveOrZero @Digits(integer = 12, fraction = 6) BigDecimal rejectedQuantity) {
    }

    /** What an audit hands over when a finding raises an NCR. */
    public record AuditFailure(
            UUID auditId,
            UUID findingId,
            String auditNumber,
            UUID supplierId,
            boolean major,
            String statement,
            String description) {
    }

    /** How the correction of an audit finding stands. */
    public record NcrOutcome(UUID id, String ncrNumber, String status, boolean finished, List<LinkedCapa> capas) {
    }

    /** What an inspection hands over when it raises an NCR. */
    public record InspectionFailure(
            UUID inspectionId,
            String inspectionNumber,
            UUID supplierId,
            UUID materialId,
            String batchNumber,
            boolean critical,
            String description,
            BigDecimal rejectedQuantity) {
    }

    public record AssignRequest(@NotNull UUID assigneeId) {
    }

    public record ContainmentRequest(@NotBlank @Size(max = MAX_TEXT) String containmentAction) {
    }

    public record InvestigationRequest(@NotBlank @Size(max = MAX_TEXT) String rootCause, UUID defectCategoryId) {
    }

    public record NoteRequest(@Size(max = MAX_TEXT) String note) {
    }

    public record RequiredNoteRequest(@NotBlank @Size(max = MAX_TEXT) String note) {
    }

    public record NcrSummary(
            UUID id,
            String ncrNumber,
            String title,
            String status,
            String severity,
            String source,
            String supplierName,
            String materialName,
            String batchNumber,
            String assigneeName,
            Instant createdAt) {
    }

    public record StatusChange(String fromStatus, String toStatus, String note, String actorName, Instant at) {
    }

    public record LinkedCapa(UUID id, String capaNumber, String title, String status) {
    }

    public record NcrDetail(
            UUID id,
            String ncrNumber,
            String title,
            String description,
            String status,
            String severity,
            String source,
            UUID inspectionId,
            UUID auditId,
            UUID supplierId,
            String supplierName,
            UUID materialId,
            String materialName,
            String batchNumber,
            UUID defectCategoryId,
            String defectCategoryName,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal rejectedQuantity,
            UUID assignedTo,
            String assigneeName,
            String containmentAction,
            String rootCause,
            String outcomeNote,
            String raisedByName,
            UUID verificationRequestedBy,
            Instant createdAt,
            Instant closedAt,
            List<LinkedCapa> capas,
            List<StatusChange> history) {
    }

    /** @param effectivenessRequired ignored for a critical or major NCR, whose fix must always be verified */
    public record CreateCapaRequest(
            UUID ncrId,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = MAX_TEXT) String problemStatement,
            @NotNull UUID ownerId,
            @NotNull @Future LocalDate targetDate,
            boolean effectivenessRequired) {
    }

    public record SaveAnalysisRequest(
            @NotBlank @Size(max = MAX_TEXT) String rootCause,
            @NotNull @Size(max = MAX_WHYS) List<@NotBlank @Size(max = 1000) String> fiveWhys,
            @NotNull @Size(max = MAX_BRANCHES) Map<@NotBlank @Size(max = 50) String,
                    @NotNull @Size(max = 20) List<@NotBlank @Size(max = 500) String>> fishbone) {
    }

    public record AddActionRequest(
            @NotNull CapaAction.Type actionType,
            @NotBlank @Size(max = MAX_TEXT) String description,
            @NotNull UUID ownerId,
            @NotNull LocalDate targetDate) {
    }

    public record CompleteActionRequest(@NotBlank @Size(max = MAX_TEXT) String note) {
    }

    public record VerifyRequest(
            @NotNull Boolean effective,
            @NotBlank @Size(max = 500) String method,
            @NotBlank @Size(max = MAX_TEXT) String note) {
    }

    public record CapaSummary(
            UUID id,
            String capaNumber,
            String title,
            String status,
            String ownerName,
            LocalDate targetDate,
            boolean overdue) {
    }

    public record ActionResponse(
            UUID id,
            String actionType,
            String description,
            UUID ownerId,
            String ownerName,
            LocalDate targetDate,
            Instant completedAt,
            String completionNote) {
    }

    public record VerificationResponse(boolean effective, String method, String note, String verifiedBy, Instant at) {
    }

    public record CapaDetail(
            UUID id,
            String capaNumber,
            String title,
            String problemStatement,
            String status,
            UUID ncrId,
            String ncrNumber,
            String rootCause,
            List<String> fiveWhys,
            Map<String, List<String>> fishbone,
            UUID ownerId,
            String ownerName,
            LocalDate targetDate,
            boolean overdue,
            boolean effectivenessRequired,
            String outcomeNote,
            Instant closedAt,
            List<ActionResponse> actions,
            List<VerificationResponse> verifications,
            List<StatusChange> history) {
    }

    /** An NCR as a trace shows it. */
    public record NcrHistory(
            UUID id, String ncrNumber, String title, String status, String severity, String batchNumber, Instant createdAt) {
    }

    /** A CAPA as a trace shows it. */
    public record CapaHistory(UUID id, String capaNumber, String title, String status, UUID ncrId, Instant createdAt) {
    }
}
