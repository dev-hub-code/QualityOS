package com.qualityos.quality.repository;

import com.qualityos.quality.entity.NonConformance;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface NonConformanceRepository
        extends JpaRepository<NonConformance, UUID>, JpaSpecificationExecutor<NonConformance> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from NonConformance n where n.id = :id")
    Optional<NonConformance> findForUpdate(UUID id);

    Optional<NonConformance> findByInspectionId(UUID inspectionId);
}
