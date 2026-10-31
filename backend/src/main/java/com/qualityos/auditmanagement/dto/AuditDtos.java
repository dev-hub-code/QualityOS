package com.qualityos.auditmanagement.dto;

import com.qualityos.auditmanagement.entity.AuditFinding;
import com.qualityos.auditmanagement.entity.AuditItem;
import com.qualityos.auditmanagement.entity.QualityAudit;
import com.qualityos.quality.dto.QualityDtos.NcrOutcome;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class AuditDtos {

    private static final int MAX_TEXT = 4000;

    private AuditDtos() {
    }

    private static String label(String code) {
        if (code == null) {
            return null;
        }
        String words = code.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    public record SaveChecklistRequest(
            @NotBlank @Size(max = 50) String code,
            @NotBlank @Size(max = 200) String name,
            @NotEmpty @Size(max = 200) List<@Valid ChecklistItemRequest> items) {
    }

    public record ChecklistItemRequest(
            @NotBlank @Size(max = 1000) String question, @Size(max = 100) String clauseReference) {
    }

    public record ChecklistItemResponse(int position, String question, String clauseReference) {
    }

    public record ChecklistSummary(UUID id, String code, String name) {
    }

    public record ChecklistDetail(UUID id, String code, String name, List<ChecklistItemResponse> items) {
    }

    public record CreateAuditRequest(
            @NotNull QualityAudit.Type auditType,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = MAX_TEXT) String scope,
            @NotNull UUID facilityId,
            UUID supplierId,
            UUID productId,
            @NotNull UUID checklistId,
            @NotNull LocalDate plannedOn,
            @NotNull UUID leadAuditorId) {
    }

    public record ReplanAuditRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = MAX_TEXT) String scope,
            @NotNull LocalDate plannedOn,
            @NotNull UUID leadAuditorId) {
    }

    public record AnswerItemRequest(@NotNull AuditItem.Result result, @Size(max = MAX_TEXT) String note) {
    }

    public record FindingRequest(
            UUID auditItemId,
            @NotNull AuditFinding.Grade grade,
            @NotBlank @Size(max = MAX_TEXT) String statement,
            @NotBlank @Size(max = MAX_TEXT) String evidence,
            @Size(max = 100) String clauseReference) {
    }

    public record CompleteAuditRequest(@NotBlank @Size(max = MAX_TEXT) String summary) {
    }

    public record NoteRequest(@NotBlank @Size(max = MAX_TEXT) String note) {
    }

    /** @param overdue planned for a day that has passed without it starting */
    public record AuditSummary(
            UUID id,
            String auditNumber,
            String auditType,
            String title,
            String status,
            LocalDate plannedOn,
            String leadAuditorName,
            boolean overdue) {
    }

    public record ItemView(
            UUID id, int position, String question, String clauseReference, String result, String note) {

        public String resultLabel() {
            return label(result);
        }
    }

    public record FindingView(
            UUID id,
            int number,
            String grade,
            String statement,
            String evidence,
            String clauseReference,
            Integer itemPosition,
            NcrOutcome ncr) {

        public String gradeLabel() {
            return label(grade);
        }
    }

    /** @param blockers why this audit cannot close yet, in words */
    public record AuditReview(
            int items, int answered, int conforming, int nonconforming, int notApplicable, List<String> blockers) {
    }

    public record AuditDetail(
            UUID id,
            String auditNumber,
            String auditType,
            String title,
            String scope,
            UUID facilityId,
            String facilityName,
            UUID supplierId,
            String supplierName,
            UUID productId,
            String productName,
            String checklistName,
            LocalDate plannedOn,
            UUID leadAuditorId,
            String leadAuditorName,
            String status,
            String summary,
            Instant startedAt,
            Instant completedAt,
            String closureRequestedByName,
            UUID closureRequestedBy,
            String closureNote,
            String closedByName,
            Instant closedAt,
            String cancelReason,
            List<ItemView> items,
            List<FindingView> findings,
            AuditReview review) {
    }

    public record AuditReport(
            String organizationName,
            String organizationLegalName,
            String organizationAddress,
            String generatedLabel,
            AuditDetail audit) {
    }
}
