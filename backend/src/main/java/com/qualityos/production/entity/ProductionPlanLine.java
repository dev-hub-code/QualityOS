package com.qualityos.production.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "production_plan_lines")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductionPlanLine extends TenantOwnedEntity {

    private UUID planId;
    private UUID productId;
    private BigDecimal quantity;
    private LocalDate dueDate;
    private String notes;

    public ProductionPlanLine(UUID planId) {
        this.planId = planId;
    }
}
