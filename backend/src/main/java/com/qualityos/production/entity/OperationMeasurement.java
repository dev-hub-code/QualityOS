package com.qualityos.production.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** A value measured for a process parameter, kept with the limits it was held to. Never changed. */
@Getter
@Entity
@Immutable
@Table(name = "operation_measurements")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OperationMeasurement extends TenantOwnedEntity {

    private UUID workOrderId;
    private UUID operationId;
    private String parameterCode;
    private String parameterName;
    private String unit;

    @Column(name = "target_value")
    private BigDecimal target;

    @Column(name = "min_value")
    private BigDecimal minimum;

    @Column(name = "max_value")
    private BigDecimal maximum;

    private BigDecimal measuredValue;
    private boolean withinLimits;
    private String comment;
    private String requestKey;

    @SuppressWarnings("java:S107")
    public OperationMeasurement(
            UUID workOrderId, UUID operationId, String parameterCode, String parameterName, String unit,
            BigDecimal target, BigDecimal minimum, BigDecimal maximum, BigDecimal measuredValue, String comment,
            String requestKey) {
        this.workOrderId = workOrderId;
        this.operationId = operationId;
        this.parameterCode = parameterCode;
        this.parameterName = parameterName;
        this.unit = unit;
        this.target = target;
        this.minimum = minimum;
        this.maximum = maximum;
        this.measuredValue = measuredValue;
        this.withinLimits = (minimum == null || measuredValue.compareTo(minimum) >= 0)
                && (maximum == null || measuredValue.compareTo(maximum) <= 0);
        this.comment = comment;
        this.requestKey = requestKey;
    }
}
