package com.qualityos.reporting.dto;

import java.time.LocalDate;
import java.util.List;

public final class ReportingDtos {

    private ReportingDtos() {
    }

    /** A rate is null when there was nothing to measure, which is different from zero. */
    public record Kpis(
            long totalInspections,
            long passedInspections,
            long failedInspections,
            Double inspectionPassRate,
            long openNcrs,
            long closedNcrs,
            long openCapas,
            long overdueCapas,
            Double supplierRejectionRate,
            Double materialRejectionRate,
            Double defectRate,
            Double firstPassYield,
            Double turnaroundHours) {
    }

    public record MonthCount(String month, long passed, long failed, long rejectedLots) {
    }

    public record CapaMonth(String month, long opened, long closed) {
    }

    public record SupplierScore(String name, long lots, long rejectedLots, Double rejectionRate, long ncrs, long openNcrs) {
    }

    public record DefectShare(String category, long ncrs, double share, double cumulativeShare) {
    }

    public record QualityShare(String name, long inspections, long passed, Double passRate) {
    }

    public record AgeBucket(String label, long ncrs) {
    }

    public record Pending(long inspectionsAwaitingApproval, long ncrsAwaitingClosure, long auditsAwaitingClosure) {
    }

    public record RecentActivity(String kind, String reference, String link, String status, String on) {
    }

    public record Overview(
            LocalDate from,
            LocalDate to,
            Kpis kpis,
            List<MonthCount> inspectionTrend,
            List<SupplierScore> suppliers,
            List<DefectShare> defects,
            List<QualityShare> products,
            List<QualityShare> facilities,
            List<AgeBucket> ncrAgeing,
            List<CapaMonth> capaTrend,
            Pending pending,
            List<RecentActivity> recent) {
    }

    public record Column(String label, int weight, boolean numeric) {

        public static Column text(String label, int weight) {
            return new Column(label, weight, false);
        }

        public static Column number(String label, int weight) {
            return new Column(label, weight, true);
        }
    }

    /** @param truncated more rows matched than a report holds, so the reader must narrow the dates */
    public record ReportTable(
            String title,
            String organizationName,
            String facilityLabel,
            String periodLabel,
            String generatedLabel,
            String note,
            List<Column> columns,
            List<List<String>> rows,
            boolean truncated) {
    }
}
