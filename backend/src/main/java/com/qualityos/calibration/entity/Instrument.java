package com.qualityos.calibration.entity;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "instruments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Instrument extends TenantOwnedEntity {

    public enum Status { ACTIVE, OUT_OF_SERVICE, RETIRED }

    private String instrumentCode;
    private String name;
    private String category;
    private String manufacturer;
    private String model;
    private String serialNumber;
    private UUID facilityId;
    private String location;
    private UUID custodianId;
    private int frequencyDays;
    private LocalDate lastCalibratedOn;
    private LocalDate nextDueOn;

    @Enumerated(EnumType.STRING)
    @Setter(AccessLevel.NONE)
    private Status status = Status.ACTIVE;

    @Setter(AccessLevel.NONE)
    private String statusReason;

    private Instant dueRemindedAt;
    private Instant overdueNotifiedAt;

    public Instrument(String instrumentCode, LocalDate nextDueOn) {
        this.instrumentCode = instrumentCode;
        this.nextDueOn = nextDueOn;
    }

    public void changeStatus(Status target, String reason) {
        requireNotRetired();
        status = target;
        statusReason = reason;
    }

    /** A failure takes the instrument out of use and leaves its due date, because nothing was proven. */
    public void calibrated(CalibrationRecord record) {
        requireNotRetired();
        if (record.getResult() == CalibrationRecord.Result.FAIL) {
            changeStatus(Status.OUT_OF_SERVICE, "Failed calibration on " + record.getCalibratedOn());
            return;
        }
        lastCalibratedOn = record.getCalibratedOn();
        nextDueOn = record.getNextDueOn();
        status = Status.ACTIVE;
        statusReason = null;
        dueRemindedAt = null;
        overdueNotifiedAt = null;
    }

    public void requireNotRetired() {
        if (status == Status.RETIRED) {
            throw new BusinessRuleException("This instrument is retired.");
        }
    }
}
