package com.qualityos.quality.service;

import java.util.UUID;

/** Answered by the calibration module, which quality must not depend on. */
public interface CalibrationDecisions {

    /** Inspections in doubt after this failed calibration that still have no decision. */
    long pending(UUID calibrationRecordId);
}
