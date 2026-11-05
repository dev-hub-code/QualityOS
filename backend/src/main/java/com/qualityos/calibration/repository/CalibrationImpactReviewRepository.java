package com.qualityos.calibration.repository;

import com.qualityos.calibration.entity.CalibrationImpactReview;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalibrationImpactReviewRepository extends JpaRepository<CalibrationImpactReview, UUID> {

    List<CalibrationImpactReview> findByInstrumentIdOrderByCreatedAtDescInspectionNumber(UUID instrumentId);

    List<CalibrationImpactReview> findByInspectionIdAndInstrumentIdIn(UUID inspectionId, Collection<UUID> instrumentIds);
}
