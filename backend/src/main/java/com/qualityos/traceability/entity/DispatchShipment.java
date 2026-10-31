package com.qualityos.traceability.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Proof that goods physically left. Append-only: the database refuses any change. */
@Getter
@Entity
@Table(name = "dispatch_shipments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DispatchShipment extends TenantOwnedEntity {

    private UUID dispatchId;
    private LocalDate shippedOn;
    private String carrierReference;

    public DispatchShipment(UUID dispatchId, LocalDate shippedOn, String carrierReference) {
        this.dispatchId = dispatchId;
        this.shippedOn = shippedOn;
        this.carrierReference = carrierReference;
    }
}
