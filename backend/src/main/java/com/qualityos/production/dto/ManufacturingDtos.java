package com.qualityos.production.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.qualityos.production.entity.Machine;
import com.qualityos.production.entity.ManufacturingModel;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ManufacturingDtos {

    private ManufacturingDtos() {
    }

    // Settings

    public record SettingsResponse(ManufacturingModel manufacturingModel, boolean separateApprover) {
    }

    public record UpdateSettingsRequest(@NotNull ManufacturingModel manufacturingModel, @NotNull Boolean separateApprover) {
    }

    /**
     * @param manufacturingModel how the product is made: its own choice, else the organisation's. Null when a hybrid
     *     organisation has not chosen for this product
     * @param chosen the product has its own choice
     */
    public record ProductSettingsResponse(UUID productId, ManufacturingModel manufacturingModel, boolean chosen) {
    }

    public record UpdateProductSettingsRequest(@NotNull ManufacturingModel manufacturingModel) {
    }

    // Work centres and machines

    public record SaveWorkCenterRequest(
            @NotBlank @Size(max = 30) String code,
            @NotBlank @Size(max = 200) String name,
            @NotNull UUID facilityId,
            UUID productionLineId,
            @Size(max = 1000) String description) {
    }

    public record WorkCenterResponse(
            UUID id,
            String code,
            String name,
            String description,
            UUID facilityId,
            String facilityCode,
            UUID productionLineId,
            String productionLineCode,
            boolean archived) {
    }

    public record WorkCenterDetail(WorkCenterResponse workCenter, List<MachineResponse> machines) {
    }

    public record SaveMachineRequest(
            @NotBlank @Size(max = 30) String code,
            @NotBlank @Size(max = 200) String name,
            @NotNull UUID workCenterId,
            @Size(max = 200) String manufacturer,
            @Size(max = 200) String model,
            @Size(max = 100) String serialNumber) {
    }

    public record ChangeMachineStatusRequest(@NotNull Machine.Status status, @Size(max = 1000) String reason) {
    }

    public record MachineResponse(
            UUID id,
            String code,
            String name,
            String manufacturer,
            String model,
            String serialNumber,
            UUID workCenterId,
            String workCenterCode,
            UUID facilityId,
            Machine.Status status,
            String statusReason,
            Instant statusChangedAt,
            /* The statuses it can change to now. */
            List<Machine.Status> nextStatuses) {
    }

    public record StatusEventResponse(
            Machine.Status fromStatus, Machine.Status toStatus, String reason, UUID changedBy, String changedByName,
            Instant changedAt) {
    }

    public record MachineDetail(MachineResponse machine, List<StatusEventResponse> history) {
    }

    // BOMs, recipes and routings

    /**
     * Starts a draft revision. It can copy another revision of the same product, or, for a BOM, the product's material
     * list. Without either it starts empty.
     */
    public record CreateRevisionRequest(
            @NotNull UUID productId,
            @Size(max = 1000) String changeNote,
            @Positive @Digits(integer = 12, fraction = 6) BigDecimal baseQuantity,
            UUID copyFromId,
            Boolean fromProductMaterials) {
    }

    public record UpdateRevisionRequest(
            @Size(max = 1000) String changeNote, @Positive @Digits(integer = 12, fraction = 6) BigDecimal baseQuantity) {
    }

    public record SendBackRequest(@NotBlank @Size(max = 1000) String note) {
    }

    /** Without a date, the revision takes effect today. */
    public record ApproveRequest(LocalDate effectiveFrom, @Size(max = 1000) String note) {
    }

    public record RetireRequest(@NotBlank @Size(max = 1000) String reason) {
    }

    public record RevisionResponse(
            UUID id,
            String kind,
            UUID productId,
            String productCode,
            String productName,
            UUID productUomId,
            int revision,
            String status,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal baseQuantity,
            String changeNote,
            String reviewNote,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            boolean inEffect,
            UUID submittedBy,
            String submittedByName,
            Instant submittedAt,
            UUID approvedBy,
            String approvedByName,
            Instant approvedAt,
            Instant obsoletedAt,
            Instant createdAt) {
    }

    /** Exactly one of {@code materialId} and {@code componentProductId}. */
    public record SaveLineRequest(
            UUID materialId,
            UUID componentProductId,
            @NotNull @Positive @Digits(integer = 12, fraction = 6) BigDecimal quantity,
            @NotNull UUID uomId,
            @DecimalMin("0") @DecimalMax(value = "100", inclusive = false) @Digits(integer = 2, fraction = 2)
            BigDecimal scrapPercent,
            Boolean scales,
            @Size(max = 1000) String notes) {
    }

    public record LineResponse(
            UUID id,
            int lineNumber,
            String componentType,
            UUID componentId,
            String componentCode,
            String componentName,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
            UUID uomId,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal scrapPercent,
            boolean scales,
            String notes) {
    }

    public record SaveParameterRequest(
            @NotBlank @Size(max = 50) String code,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 30) String unit,
            @Digits(integer = 12, fraction = 6) BigDecimal target,
            @Digits(integer = 12, fraction = 6) BigDecimal minimum,
            @Digits(integer = 12, fraction = 6) BigDecimal maximum) {
    }

    public record ParameterResponse(
            UUID id,
            String code,
            String name,
            String unit,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal target,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal minimum,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal maximum) {
    }

    public record BomDetail(RevisionResponse revision, List<LineResponse> lines, List<ParameterResponse> parameters) {
    }

    /**
     * One row of a multi-level explosion, in tree order.
     *
     * @param quantity in the component's own unit, scrap included
     * @param exploded the component is a product whose own BOM or recipe is listed beneath it
     */
    public record ExplosionRow(
            int level,
            String componentType,
            UUID componentId,
            String componentCode,
            String componentName,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
            UUID uomId,
            boolean exploded) {
    }

    /** What the tree's leaves add up to: what has to be bought or found. */
    public record RequirementTotal(
            String componentType,
            UUID componentId,
            String componentCode,
            String componentName,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
            UUID uomId) {
    }

    public record ExplosionResponse(
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
            UUID uomId,
            LocalDate on,
            List<ExplosionRow> rows,
            List<RequirementTotal> totals) {
    }

    public record ScaledLine(
            int lineNumber,
            String componentType,
            UUID componentId,
            String componentCode,
            String componentName,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
            UUID uomId,
            boolean scales) {
    }

    public record ScaledRecipe(
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal batchSize,
            UUID uomId,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal factor,
            List<ScaledLine> lines,
            List<ParameterResponse> parameters) {
    }

    public record SaveOperationRequest(
            @NotNull @Min(1) @Max(99999) Integer sequence,
            @NotBlank @Size(max = 200) String name,
            @NotNull UUID workCenterId,
            @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal setupMinutes,
            @DecimalMin("0") @Digits(integer = 8, fraction = 4) BigDecimal runMinutes,
            @Size(max = 4000) String instructions) {
    }

    public record OperationResponse(
            UUID id,
            int sequence,
            String name,
            UUID workCenterId,
            String workCenterCode,
            String workCenterName,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal setupMinutes,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal runMinutes,
            String instructions,
            List<ParameterResponse> parameters) {
    }

    public record RoutingDetail(RevisionResponse revision, List<OperationResponse> operations) {
    }
}
