package com.qualityos.recall.repository;

import com.qualityos.recall.entity.RecallBatch;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecallBatchRepository extends JpaRepository<RecallBatch, UUID> {

    List<RecallBatch> findByRecallIdIn(Collection<UUID> recallIds);
}
