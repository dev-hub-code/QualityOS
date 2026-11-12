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
import lombok.Setter;

/** A value a recipe or an operation must hold, such as a temperature, with its target and limits. */
@Getter
@Setter
@Entity
@Table(name = "process_parameters")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessParameter extends TenantOwnedEntity {

    private UUID bomId;
    private UUID operationId;
    private String code;
    private String name;
    private String unit;

    @Column(name = "target_value")
    private BigDecimal target;

    @Column(name = "min_value")
    private BigDecimal minimum;

    @Column(name = "max_value")
    private BigDecimal maximum;

    public static ProcessParameter ofRecipe(UUID bomId) {
        ProcessParameter parameter = new ProcessParameter();
        parameter.bomId = bomId;
        return parameter;
    }

    public static ProcessParameter ofOperation(UUID operationId) {
        ProcessParameter parameter = new ProcessParameter();
        parameter.operationId = operationId;
        return parameter;
    }
}
