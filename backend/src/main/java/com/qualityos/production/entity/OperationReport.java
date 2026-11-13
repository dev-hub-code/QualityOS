package com.qualityos.production.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * Quantities reported on an operation, in the work order's unit. A reversal carries the same quantities negated and
 * points at the report it cancels. Never changed.
 */
@Getter
@Entity
@Immutable
@Table(name = "operation_reports")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OperationReport extends TenantOwnedEntity {

    private UUID workOrderId;
    private UUID operationId;
    private BigDecimal goodQuantity;
    private BigDecimal rejectedQuantity;
    private BigDecimal scrapQuantity;
    private BigDecimal reworkQuantity;
    private UUID reversesReportId;
    private String comment;
    private String requestKey;

    public OperationReport(
            UUID workOrderId, UUID operationId, BigDecimal good, BigDecimal rejected, BigDecimal scrap,
            BigDecimal rework, String comment, String requestKey) {
        this.workOrderId = workOrderId;
        this.operationId = operationId;
        this.goodQuantity = good;
        this.rejectedQuantity = rejected;
        this.scrapQuantity = scrap;
        this.reworkQuantity = rework;
        this.comment = comment;
        this.requestKey = requestKey;
    }

    public OperationReport reversal(String reason) {
        OperationReport reversal = new OperationReport(workOrderId, operationId, goodQuantity.negate(),
                rejectedQuantity.negate(), scrapQuantity.negate(), reworkQuantity.negate(), reason, null);
        reversal.reversesReportId = getId();
        return reversal;
    }
}
