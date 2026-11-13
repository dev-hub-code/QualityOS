package com.qualityos.calibration.service;

import com.qualityos.calibration.repository.CalibrationImpactReviewRepository;
import com.qualityos.quality.service.CalibrationDecisions;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class PendingImpactReviews implements CalibrationDecisions {

    private final CalibrationImpactReviewRepository reviews;

    @Override
    public long pending(UUID calibrationRecordId) {
        return reviews.countByCalibrationRecordIdAndDecisionIsNull(calibrationRecordId);
    }
}
