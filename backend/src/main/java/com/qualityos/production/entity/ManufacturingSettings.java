package com.qualityos.production.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "manufacturing_settings")
@NoArgsConstructor
public class ManufacturingSettings extends TenantOwnedEntity {

    @Enumerated(EnumType.STRING)
    private ManufacturingModel manufacturingModel = ManufacturingModel.DISCRETE;

    private boolean separateApprover = true;

    /** Releasing a work order needs an approved BOM or recipe in effect. */
    private boolean workOrderRequiresBom = true;

    /** Releasing a work order needs an approved routing in effect. */
    private boolean workOrderRequiresRouting;

    /** Release reserves what the order needs from the oldest free lots. */
    private boolean reserveOnRelease;

    /** Production starts only once every material is reserved and every sub-assembly is in stock. */
    private boolean startRequiresMaterials;

    /** More than the order needs may be issued. */
    private boolean allowOverIssue;

    /** An operation becomes ready only once the one before it is complete. */
    private boolean operationsInSequence = true;

    /** How far past the order quantity the first operation may report, in percent. */
    private BigDecimal overReportPercent = BigDecimal.ZERO;

    /** Starting an operation at a work centre that has machines needs one of them. */
    private boolean operationRequiresMachine;
}
