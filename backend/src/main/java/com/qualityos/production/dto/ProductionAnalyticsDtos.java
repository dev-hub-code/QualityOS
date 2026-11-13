package com.qualityos.production.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.qualityos.production.dto.CostingDtos.CostSummary;
import com.qualityos.production.dto.DowntimeDtos.SummaryRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Production analytics for a period: days in the organisation's time zone, both included. Quantities are in each
 * product's own unit, so they are only added up per product. Times are minutes. Percentages are 0 to 100, rounded to
 * one place, and null when the data needed is missing: never shown as zero.
 */
public final class ProductionAnalyticsDtos {

    private ProductionAnalyticsDtos() {
    }

    public record ProductOutput(
            UUID productId,
            String productCode,
            String productName,
            UUID uomId,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal ordered,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal received,
            long orders) {
    }

    public record StatusCount(String status, long count) {
    }

    /** Operation reports in the period, reversals netted out, in the operations' units. */
    public record QualityRates(
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal processed,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal good,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal rejected,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal scrap,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal rework,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal rejectionRate,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal scrapRate,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal reworkRate) {
    }

    /** Planned setup and run time against running time, for operations completed in the period. */
    public record Efficiency(
            long operations,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal plannedMinutes,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal actualMinutes,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal percent) {
    }

    /** Material lines at finished-goods receipts in the period. */
    public record MaterialVarianceSummary(
            long receipts,
            long lines,
            long linesOutside,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal averageAbsolutePercent) {
    }

    /**
     * OEE = availability × performance × quality.
     * Availability = running time / planned production time (shift time less planned downtime).
     * Performance = (ideal cycle time × units reported) / running time; the ideal cycle time is the routing's run
     * time per unit.
     * Quality = good units / units reported.
     * Utilisation = running time / shift time.
     */
    public record MachineOee(
            UUID machineId,
            String machineCode,
            String workCenterCode,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal shiftMinutes,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal plannedDowntimeMinutes,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal plannedProductionMinutes,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal runningMinutes,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal unplannedDowntimeMinutes,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal utilisation,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal availability,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal performance,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quality,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal oee,
            /** Why a figure is missing, or what to read it with. */
            List<String> notes) {
    }

    public record ProductionAnalytics(
            LocalDate from,
            LocalDate to,
            String timezone,
            UUID facilityId,
            /** Finished goods received in the period, by product. */
            List<ProductOutput> production,
            /** Orders closed in the period: ordered against received, by product. */
            List<ProductOutput> plannedVersusActual,
            long ordersCompleted,
            long ordersPartiallyCompleted,
            long ordersOnTime,
            /** Every work order now, by status. */
            List<StatusCount> workOrderStatus,
            QualityRates quality,
            Efficiency efficiency,
            MaterialVarianceSummary materialVariance,
            List<MachineOee> machines,
            /** All machines together, on the same definitions. */
            MachineOee overall,
            List<SummaryRow> downtimeByCategory,
            /** Present only for those who may see production cost. */
            CostSummary cost) {
    }
}
