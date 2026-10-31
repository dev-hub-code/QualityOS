package com.qualityos.inspection.repository;

import com.qualityos.inspection.entity.InspectionApproval;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InspectionApprovalRepository extends JpaRepository<InspectionApproval, UUID> {

    List<InspectionApproval> findByInspectionIdOrderByCreatedAtAsc(UUID inspectionId);
}
