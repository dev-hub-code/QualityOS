package com.qualityos.production.repository;

import com.qualityos.production.entity.RoutingOperation;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutingOperationRepository extends JpaRepository<RoutingOperation, UUID> {

    List<RoutingOperation> findByRoutingIdOrderBySequence(UUID routingId);

    boolean existsByRoutingIdAndSequence(UUID routingId, int sequence);
}
