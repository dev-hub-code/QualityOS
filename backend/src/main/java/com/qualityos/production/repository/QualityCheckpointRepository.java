package com.qualityos.production.repository;

import com.qualityos.production.entity.QualityCheckpoint;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface QualityCheckpointRepository
        extends JpaRepository<QualityCheckpoint, UUID>, JpaSpecificationExecutor<QualityCheckpoint> {

    List<QualityCheckpoint> findByArchivedAtIsNullOrderByStageAscNameAsc();
}
