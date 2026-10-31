package com.qualityos.inspection.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.qualityos.inspection.dto.TemplateDtos.SpecSnapshot;
import com.qualityos.inspection.entity.Inspection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class InspectionDtos {

    private InspectionDtos() {
    }

    public record CreateInspectionRequest(@NotNull UUID receiptItemId, @NotNull UUID templateId) {
    }

    public record ExecuteRequest(@NotEmpty List<@Valid ResultRequest> results) {
    }

    public record ResultRequest(
            @NotBlank @Size(max = 50) String parameterCode,
            @Min(1) int sampleNumber,
            @Digits(integer = 12, fraction = 6) BigDecimal valueNumeric,
            @Size(max = 500) String valueText,
            Boolean valueBoolean,
            LocalDate valueDate,
            @Size(max = 1000) String remarks) {
    }

    public record ApproveRequest(Inspection.Result result, @Size(max = 1000) String note) {
    }

    public record SendBackRequest(@NotBlank @Size(max = 1000) String note) {
    }

    public record DispositionRequest(@NotNull Inspection.Disposition disposition, @Size(max = 1000) String note) {
    }

    public record InspectionSummary(
            UUID id,
            String inspectionNumber,
            String inspectionType,
            String supplierName,
            String materialCode,
            String materialName,
            String batchNumber,
            String status,
            String evaluatedResult,
            String finalResult,
            String disposition,
            Instant createdAt) {
    }

    public record InspectionDetail(
            UUID id,
            String inspectionNumber,
            String inspectionType,
            UUID receiptItemId,
            String supplierName,
            String materialCode,
            String materialName,
            String batchNumber,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
            int sampleSize,
            String status,
            String evaluatedResult,
            String finalResult,
            String disposition,
            Instant submittedAt,
            Instant approvedAt,
            SpecSnapshot specification,
            List<ResultResponse> results,
            List<DecisionResponse> decisions) {
    }

    public record ResultResponse(
            String parameterCode,
            int sampleNumber,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal valueNumeric,
            String valueText,
            Boolean valueBoolean,
            LocalDate valueDate,
            String evaluation,
            String remarks) {
    }

    public record DecisionResponse(String decision, String note, UUID decidedBy, Instant decidedAt) {
    }
}
