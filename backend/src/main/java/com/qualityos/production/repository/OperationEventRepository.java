package com.qualityos.production.repository;

import com.qualityos.production.entity.OperationEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperationEventRepository extends JpaRepository<OperationEvent, UUID> {

    List<OperationEvent> findByOperationIdOrderByCreatedAtAscIdAsc(UUID operationId);
}
