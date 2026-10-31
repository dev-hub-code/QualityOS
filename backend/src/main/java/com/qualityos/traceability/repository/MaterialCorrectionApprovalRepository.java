package com.qualityos.traceability.repository;

import com.qualityos.traceability.entity.MaterialCorrectionApproval;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaterialCorrectionApprovalRepository extends JpaRepository<MaterialCorrectionApproval, UUID> {

    List<MaterialCorrectionApproval> findByCorrectionIdOrderByCreatedAt(UUID correctionId);
}
