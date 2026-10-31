package com.qualityos.calibration.repository;

import com.qualityos.calibration.entity.InstrumentUsage;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstrumentUsageRepository extends JpaRepository<InstrumentUsage, UUID> {

    List<InstrumentUsage> findByInstrumentIdOrderByUsedOnDesc(UUID instrumentId);

    List<InstrumentUsage> findByInspectionId(UUID inspectionId);
}
