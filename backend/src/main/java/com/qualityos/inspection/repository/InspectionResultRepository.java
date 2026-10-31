package com.qualityos.inspection.repository;

import com.qualityos.inspection.entity.InspectionResult;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InspectionResultRepository extends JpaRepository<InspectionResult, UUID> {

    List<InspectionResult> findByInspectionIdOrderByParameterCodeAscSampleNumberAsc(UUID inspectionId);
}
