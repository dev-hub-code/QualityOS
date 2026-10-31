package com.qualityos.traceability.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Append-only: the database refuses any change. */
@Getter
@Entity
@Table(name = "dispatch_records")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DispatchRecord extends TenantOwnedEntity {

    private String dispatchNumber;
    private UUID customerId;
    private LocalDate dispatchedOn;
    private String reference;

    public DispatchRecord(String dispatchNumber, UUID customerId, LocalDate dispatchedOn, String reference) {
        this.dispatchNumber = dispatchNumber;
        this.customerId = customerId;
        this.dispatchedOn = dispatchedOn;
        this.reference = reference;
    }
}
