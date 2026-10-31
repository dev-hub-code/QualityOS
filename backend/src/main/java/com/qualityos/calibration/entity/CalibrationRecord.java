package com.qualityos.calibration.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "calibration_records")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class CalibrationRecord extends TenantOwnedEntity {

    public enum Result { PASS, ADJUSTED, FAIL }

    private UUID instrumentId;
    private LocalDate calibratedOn;

    @Enumerated(EnumType.STRING)
    private Result result;

    private String performedBy;
    private String certificateNumber;
    private String notes;
    private LocalDate nextDueOn;
}
