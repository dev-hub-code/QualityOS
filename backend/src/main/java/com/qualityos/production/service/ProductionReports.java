package com.qualityos.production.service;

import static com.qualityos.reporting.dto.ReportingDtos.Column.number;
import static com.qualityos.reporting.dto.ReportingDtos.Column.text;

import com.qualityos.production.dto.CostingDtos.CostSummary;
import com.qualityos.production.dto.CostingDtos.CostSummaryRow;
import com.qualityos.production.dto.ProductionAnalyticsDtos.MachineOee;
import com.qualityos.production.dto.ProductionAnalyticsDtos.ProductionAnalytics;
import com.qualityos.reporting.service.ComputedReport;
import com.qualityos.reporting.service.ReportType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OEE and production cost as reports, worked out by the same services as the screens so that both agree. */
@Configuration
class ProductionReports {

    @Bean
    ComputedReport oeeReport(ProductionAnalyticsService analytics) {
        return new ComputedReport() {
            @Override
            public ReportType type() {
                return ReportType.OEE;
            }

            @Override
            public Table rows(LocalDate from, LocalDate to, UUID facilityId) {
                ProductionAnalytics figures = analytics.analytics(from, to, facilityId, false);
                List<List<String>> rows = new ArrayList<>();
                figures.machines().forEach(machine -> rows.add(oeeRow(machine.machineCode(),
                        machine.workCenterCode(), machine)));
                if (!figures.machines().isEmpty()) {
                    rows.add(oeeRow("All machines", "", figures.overall()));
                }
                return new Table(List.of(text("Machine", 9), text("Work centre", 9), number("Shift min", 7),
                        number("Planned stop min", 7), number("Running min", 7), number("Unplanned stop min", 7),
                        number("Utilisation %", 7), number("Availability %", 7), number("Performance %", 7),
                        number("Quality %", 7), number("OEE %", 6), text("Notes", 22)), rows,
                        "Active machines in the period. OEE is availability times performance times quality. A blank"
                                + " figure could not be worked out; the notes say why.");
            }
        };
    }

    @Bean
    ComputedReport costReport(CostingService costing, ProductionCalendar calendar) {
        return new ComputedReport() {
            @Override
            public ReportType type() {
                return ReportType.PRODUCTION_COST;
            }

            @Override
            public Table rows(LocalDate from, LocalDate to, UUID facilityId) {
                CostSummary summary = costing.summary(from, to, facilityId);
                ZoneId zone = calendar.zone();
                List<List<String>> rows = new ArrayList<>();
                for (CostSummaryRow order : summary.orders()) {
                    rows.add(Arrays.asList(order.workOrderNumber(), order.productCode(), order.status().name(),
                            order.completedAt() == null ? null
                                    : LocalDate.ofInstant(order.completedAt(), zone).toString(),
                            plain(order.orderedQuantity()), plain(order.receivedQuantity()),
                            plain(order.plannedTotal()), plain(order.actualTotal()), plain(order.plannedUnit()),
                            plain(order.actualUnit()), order.complete() ? "Yes" : "No"));
                }
                if (!rows.isEmpty()) {
                    rows.add(Arrays.asList("Total", null, null, null, null, null, plain(summary.plannedTotal()),
                            plain(summary.actualTotal()), null, null, summary.complete() ? "Yes" : "No"));
                }
                return new Table(List.of(text("Work order", 12), text("Product", 10), text("Status", 11),
                        text("Completed", 9), number("Ordered", 8), number("Received", 8),
                        number("Planned cost", 10), number("Actual cost", 10), number("Planned unit", 9),
                        number("Actual unit", 9), text("All priced", 6)), rows,
                        "Work orders closed in the period, in " + summary.currency() + ". Where a price or rate is"
                                + " missing the cost is short, and All priced says No; the work order shows what is"
                                + " missing.");
            }
        };
    }

    private static List<String> oeeRow(String machine, String centre, MachineOee oee) {
        return Arrays.asList(machine, centre, plain(oee.shiftMinutes()), plain(oee.plannedDowntimeMinutes()),
                plain(oee.runningMinutes()), plain(oee.unplannedDowntimeMinutes()), plain(oee.utilisation()),
                plain(oee.availability()), plain(oee.performance()), plain(oee.quality()), plain(oee.oee()),
                String.join(" ", oee.notes()));
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
