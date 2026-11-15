package com.qualityos.calibration.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Lets another module say what an instrument measured, so that a failed calibration reaches it. Production, for one,
 * reports the operations whose measurements named the instrument. Calibration cannot depend on those modules, so each
 * registers a source.
 */
public interface InstrumentUseSource {

    /** Each record that used the instrument from {@code from} to {@code to}, both included, newest first. */
    List<InstrumentUse> usesBetween(UUID instrumentId, LocalDate from, LocalDate to);

    /**
     * @param label how the record is named, such as "WO-2026-00001 operation 10"
     * @param link the app path of the record
     * @param usedOn the last day it was used on that record within the period asked
     */
    record InstrumentUse(String label, String link, LocalDate usedOn) {
    }
}
