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

/**
 * One inspection measured with an instrument that later failed calibration. Kept from the moment of failure, so the
 * question stays open until someone answers it, even after the instrument passes again.
 */
@Getter
@Entity
@Table(name = "calibration_impact_reviews")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CalibrationImpactReview extends TenantOwnedEntity {

    /** RESULT_STANDS: the measurement is still trusted. REINSPECT: the goods must be inspected again. */
    public enum Decision { RESULT_STANDS, REINSPECT }

    private UUID calibrationRecordId;
    private UUID instrumentId;
    private UUID inspectionId;
    private String inspectionNumber;
    private LocalDate usedOn;

    @Enumerated(EnumType.STRING)
    private Decision decision;

    private String note;
    private UUID decidedBy;
    private Instant decidedAt;

    public CalibrationImpactReview(UUID calibrationRecordId, InstrumentUsage usage) {
        this.calibrationRecordId = calibrationRecordId;
        this.instrumentId = usage.getInstrumentId();
        this.inspectionId = usage.getInspectionId();
        this.inspectionNumber = usage.getInspectionNumber();
        this.usedOn = usage.getUsedOn();
    }

    /** Decided once. The database refuses any later change. */
    public void decide(Decision decided, String reason, UUID actor, Instant now) {
        if (decision != null) {
            throw new BusinessRuleException("This inspection has already been reviewed.");
        }
        decision = decided;
        note = reason;
        decidedBy = actor;
        decidedAt = now;
    }
}
