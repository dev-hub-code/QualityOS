package com.qualityos.inspection.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.qualityos.inspection.entity.InspectionParameter.Criticality;
import com.qualityos.inspection.entity.InspectionParameter.DataType;
import com.qualityos.inspection.entity.InspectionTemplate;
import com.qualityos.inspection.entity.TemplateVersion.SamplingType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class TemplateDtos {

    private TemplateDtos() {
    }

    public record SaveTemplateRequest(
            @NotBlank @Size(max = 50) String code,
            @NotBlank @Size(max = 200) String name,
            @NotNull InspectionTemplate.Type inspectionType,
            UUID materialId,
            UUID productId) {
    }

    public record TemplateSummary(
            UUID id,
            String code,
            String name,
            String inspectionType,
            UUID materialId,
            UUID productId,
            Integer publishedVersion,
            boolean hasDraft) {
    }

    public record TemplateDetail(TemplateSummary template, List<VersionResponse> versions) {
    }

    public record SaveDraftRequest(
            @Size(max = 1000) String changeNote,
            @NotNull SamplingType samplingType,
            @Min(1) @jakarta.validation.constraints.Max(500) Integer sampleSize,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("100") @Digits(integer = 3, fraction = 2)
            BigDecimal samplePercent,
            @Min(0) int acceptanceNumber,
            @NotNull List<@Valid ParameterRequest> parameters) {
    }

    public record ParameterRequest(
            @NotBlank @Size(max = 50) String code,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 1000) String description,
            @NotNull DataType dataType,
            UUID uomId,
            @Digits(integer = 12, fraction = 6) BigDecimal nominalValue,
            @Digits(integer = 12, fraction = 6) BigDecimal minValue,
            @Digits(integer = 12, fraction = 6) BigDecimal maxValue,
            @Digits(integer = 12, fraction = 6) @DecimalMin("0") BigDecimal tolerance,
            @Size(max = 500) String expectedText,
            @NotNull Criticality criticality,
            boolean mandatory,
            boolean perSample,
            boolean evidenceRequired,
            @Size(max = 200) String inspectionMethod,
            @Size(max = 1000) String remarks) {
    }

    public record PublishRequest(@NotNull LocalDate effectiveFrom) {
    }

    public record VersionResponse(
            UUID id,
            int versionNumber,
            String status,
            String changeNote,
            String samplingType,
            Integer sampleSize,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal samplePercent,
            int acceptanceNumber,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Instant publishedAt,
            List<ParameterSpec> parameters) {
    }

    /** A parameter's full definition. This is also what an inspection's snapshot stores. */
    public record ParameterSpec(
            String code,
            String name,
            String description,
            DataType dataType,
            UUID uomId,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal nominalValue,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal minValue,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal maxValue,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal tolerance,
            String expectedText,
            Criticality criticality,
            boolean mandatory,
            boolean perSample,
            boolean evidenceRequired,
            String inspectionMethod,
            String remarks) {
    }

    /** The specification frozen into an inspection. */
    public record SpecSnapshot(
            String templateCode,
            String templateName,
            int versionNumber,
            String samplingType,
            int acceptanceNumber,
            List<ParameterSpec> parameters) {
    }
}
