package com.qualityos.calibration.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "instrument_usages")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class InstrumentUsage extends TenantOwnedEntity {

    private UUID instrumentId;
    private UUID inspectionId;
    private String inspectionNumber;
    private LocalDate usedOn;
}
