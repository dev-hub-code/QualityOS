package com.qualityos.traceability.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Entity
@Table(name = "finished_products")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FinishedProduct extends TenantOwnedEntity {

    private String serialNumber;
    private UUID productionBatchId;

    @Setter
    private UUID dispatchItemId;

    public FinishedProduct(String serialNumber, UUID productionBatchId) {
        this.serialNumber = serialNumber;
        this.productionBatchId = productionBatchId;
    }
}
