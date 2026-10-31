package com.qualityos.reporting.service;

import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.security.Permissions;
import java.util.Arrays;

/** A report needs REPORT_VIEW and the permission to see what it lists. */
public enum ReportType {
    INSPECTION("inspection", "Inspection report", Permissions.INSPECTION_VIEW),
    SUPPLIER_QUALITY("supplier-quality", "Supplier quality report", Permissions.SUPPLIER_VIEW),
    MATERIAL_REJECTION("material-rejection", "Material rejection report", Permissions.INSPECTION_VIEW),
    NCR("ncr", "NCR report", Permissions.NCR_VIEW),
    CAPA("capa", "CAPA report", Permissions.CAPA_VIEW),
    CALIBRATION("calibration", "Calibration report", Permissions.CALIBRATION_VIEW),
    AUDIT("audit", "Audit report", Permissions.AUDIT_VIEW),
    BATCH_TRACEABILITY("batch-traceability", "Batch traceability report", Permissions.TRACEABILITY_VIEW);

    private final String slug;
    private final String title;
    private final String permission;

    ReportType(String slug, String title, String permission) {
        this.slug = slug;
        this.title = title;
        this.permission = permission;
    }

    public String slug() {
        return slug;
    }

    public String title() {
        return title;
    }

    public String permission() {
        return permission;
    }

    public static ReportType of(String slug) {
        return Arrays.stream(values())
                .filter(type -> type.slug.equals(slug))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Report", slug));
    }
}
