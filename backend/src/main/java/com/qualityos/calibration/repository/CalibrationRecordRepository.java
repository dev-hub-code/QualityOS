package com.qualityos.calibration.repository;

import com.qualityos.calibration.entity.CalibrationRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalibrationRecordRepository extends JpaRepository<CalibrationRecord, UUID> {

    List<CalibrationRecord> findByInstrumentIdOrderByCalibratedOnDescCreatedAtDesc(UUID instrumentId);
}
