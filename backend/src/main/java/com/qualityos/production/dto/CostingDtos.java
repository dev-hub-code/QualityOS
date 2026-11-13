package com.qualityos.production.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.qualityos.production.entity.WorkOrder;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Money is in the organisation's currency, rounded to two decimal places. A null amount is one that is not known. */
public final class CostingDtos {

    private CostingDtos() {
    }

    public record MaterialCost(
            int lineNumber,
            String componentCode,
            String componentName,
            UUID uomId,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal plannedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal plannedCost,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal issuedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal actualCost) {
    }

    public record OperationCost(
            int sequence,
            String name,
            String workCenterCode,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal plannedHours,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal actualHours,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal plannedCost,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal actualCost) {
    }

    /** Scrap and rework are parts of the total, shown on their own; they are not added to it again. */
    public record CostTotals(
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal material,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal labour,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal machine,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal overhead,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal scrap,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal rework,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal total,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal unit) {
    }

    /**
     * Planned against actual cost of one work order. {@code complete} is false when a price or rate is missing; the
     * totals then cover only what is known, and {@code missing} says what is not.
     */
    public record WorkOrderCost(
            UUID workOrderId,
            String workOrderNumber,
            WorkOrder.Status status,
            String currency,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal orderedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal receivedQuantity,
            List<MaterialCost> materials,
            List<OperationCost> operations,
            CostTotals planned,
            CostTotals actual,
            boolean complete,
            List<String> missing) {
    }

    public record CostSummaryRow(
            UUID workOrderId,
            String workOrderNumber,
            String productCode,
            WorkOrder.Status status,
            Instant completedAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal orderedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal receivedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal plannedTotal,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal actualTotal,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal plannedUnit,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal actualUnit,
            boolean complete) {
    }

    /** Orders closed in the period, both days included, in the organisation's time zone. */
    public record CostSummary(
            LocalDate from,
            LocalDate to,
            String currency,
            List<CostSummaryRow> orders,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal plannedTotal,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal actualTotal,
            /** False when any order's cost is incomplete. */
            boolean complete) {
    }
}
