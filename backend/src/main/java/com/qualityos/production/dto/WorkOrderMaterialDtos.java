package com.qualityos.production.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.qualityos.production.entity.MaterialMovement;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** A work order's materials: what it needs, what is set aside and issued, and what is short. */
public final class WorkOrderMaterialDtos {

    private WorkOrderMaterialDtos() {
    }

    /** @param quantity in the material line's unit */
    public record ReserveRequest(
            @NotNull Integer lineNumber,
            @NotNull UUID lotId,
            @NotNull @Positive @Digits(integer = 12, fraction = 6) BigDecimal quantity) {
    }

    /**
     * Exactly one of {@code lotId} and {@code sourceBatchId}.
     *
     * @param quantity in the material line's unit
     */
    public record IssueRequest(
            @NotNull Integer lineNumber,
            UUID lotId,
            UUID sourceBatchId,
            @NotNull @Positive @Digits(integer = 12, fraction = 6) BigDecimal quantity) {
    }

    /** @param quantity in the unit of the source it came from */
    public record ReturnRequest(
            @NotNull UUID consumptionId,
            @NotNull @Positive @Digits(integer = 12, fraction = 6) BigDecimal quantity,
            @NotBlank @Size(max = 1000) String reason) {
    }

    public record UnreserveRequest(@Size(max = 1000) String reason) {
    }

    /** A lot or released batch the line could draw on, in the source's unit. */
    public record SourceOption(
            String sourceType,
            UUID id,
            String number,
            LocalDate receivedOn,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal freeQuantity,
            UUID uomId) {
    }

    /**
     * One material line, all in its own unit.
     *
     * @param outstanding needed and neither issued nor reserved
     * @param shortage outstanding beyond what the facility has free
     */
    public record MaterialLine(
            int lineNumber,
            String componentType,
            UUID componentId,
            String componentCode,
            String componentName,
            UUID uomId,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal required,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal issued,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal reserved,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal outstanding,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal available,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal shortage,
            List<SourceOption> sources) {
    }

    public record ReservationResponse(
            UUID id,
            int lineNumber,
            UUID lotId,
            String lotNumber,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal openQuantity,
            UUID uomId,
            Instant createdAt) {
    }

    /**
     * Something that went into the order's batch and still counts, whether issued here or recorded on the batch.
     *
     * @param lineNumber the material line it counts against, or null when the order does not list it
     */
    public record ConsumptionResponse(
            UUID id,
            Integer lineNumber,
            String sourceType,
            UUID sourceId,
            String sourceNumber,
            String componentCode,
            String componentName,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
            UUID uomId) {
    }

    public record MovementResponse(
            UUID id,
            MaterialMovement.Type movementType,
            Integer lineNumber,
            String sourceNumber,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
            UUID uomId,
            String reason,
            UUID movedBy,
            String movedByName,
            Instant movedAt) {
    }

    /**
     * @param shortages one sentence per line that is not covered, empty when everything is
     */
    public record MaterialsResponse(
            List<MaterialLine> lines,
            List<ReservationResponse> reservations,
            List<ConsumptionResponse> consumptions,
            List<MovementResponse> movements,
            List<String> shortages) {
    }
}
