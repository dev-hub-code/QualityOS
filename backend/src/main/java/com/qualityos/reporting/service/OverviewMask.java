package com.qualityos.reporting.service;

import static com.qualityos.common.security.Permissions.AUDIT_VIEW;
import static com.qualityos.common.security.Permissions.CAPA_VIEW;
import static com.qualityos.common.security.Permissions.INSPECTION_VIEW;
import static com.qualityos.common.security.Permissions.MANUFACTURING_VIEW;
import static com.qualityos.common.security.Permissions.NCR_VIEW;
import static com.qualityos.common.security.Permissions.SUPPLIER_VIEW;
import static com.qualityos.common.security.Permissions.WORK_ORDER_VIEW;

import com.qualityos.reporting.dto.ReportingDtos.Kpis;
import com.qualityos.reporting.dto.ReportingDtos.Overview;
import com.qualityos.reporting.dto.ReportingDtos.Pending;
import com.qualityos.reporting.dto.ReportingDtos.ProductionFigures;
import java.util.Map;
import java.util.Set;

/**
 * Takes out of the organisation's figures what this person may not view. Each figure follows the permission its
 * report needs: inspection figures need inspection view, NCR figures NCR view, and so on. The figures are worked out
 * once for everyone and kept, so this runs on every request.
 */
final class OverviewMask {

    static final int RECENT = 8;

    /** The permission that each kind of recent activity needs. */
    private static final Map<String, String> RECENT_KINDS = Map.of("Inspection", INSPECTION_VIEW, "NCR", NCR_VIEW);

    private OverviewMask() {
    }

    static Overview visibleTo(Overview all, Set<String> held) {
        boolean inspections = held.contains(INSPECTION_VIEW);
        boolean ncrs = held.contains(NCR_VIEW);
        boolean capas = held.contains(CAPA_VIEW);
        boolean suppliers = held.contains(SUPPLIER_VIEW);
        Kpis k = all.kpis();
        Kpis kpis = new Kpis(
                inspections ? k.totalInspections() : null,
                inspections ? k.passedInspections() : null,
                inspections ? k.failedInspections() : null,
                inspections ? k.inspectionPassRate() : null,
                ncrs ? k.openNcrs() : null,
                ncrs ? k.closedNcrs() : null,
                capas ? k.openCapas() : null,
                capas ? k.overdueCapas() : null,
                suppliers ? k.supplierRejectionRate() : null,
                inspections ? k.materialRejectionRate() : null,
                inspections ? k.defectRate() : null,
                inspections ? k.firstPassYield() : null,
                inspections ? k.turnaroundHours() : null);
        Pending p = all.pending();
        Pending pending = new Pending(
                inspections ? p.inspectionsAwaitingApproval() : null,
                ncrs ? p.ncrsAwaitingClosure() : null,
                held.contains(AUDIT_VIEW) ? p.auditsAwaitingClosure() : null);
        return new Overview(
                all.from(),
                all.to(),
                kpis,
                inspections ? all.inspectionTrend() : null,
                suppliers ? all.suppliers() : null,
                ncrs ? all.defects() : null,
                inspections ? all.products() : null,
                inspections ? all.facilities() : null,
                ncrs ? all.ncrAgeing() : null,
                capas ? all.capaTrend() : null,
                pending,
                all.recent().stream()
                        .filter(item -> held.contains(RECENT_KINDS.getOrDefault(item.kind(), INSPECTION_VIEW)))
                        .limit(RECENT)
                        .toList(),
                production(all.production(), held));
    }

    private static ProductionFigures production(ProductionFigures all, Set<String> held) {
        if (all == null || !held.contains(WORK_ORDER_VIEW)) {
            return null;
        }
        return new ProductionFigures(all.openWorkOrders(), all.lateWorkOrders(), all.completedWorkOrders(),
                all.onTimeRate(), all.lossRate(),
                held.contains(MANUFACTURING_VIEW) ? all.unplannedDowntimeMinutes() : null);
    }
}
