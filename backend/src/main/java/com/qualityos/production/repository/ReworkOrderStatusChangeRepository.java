package com.qualityos.production.repository;

import com.qualityos.production.entity.ReworkOrderStatusChange;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReworkOrderStatusChangeRepository extends JpaRepository<ReworkOrderStatusChange, UUID> {

    List<ReworkOrderStatusChange> findByReworkOrderIdOrderByCreatedAtAscIdAsc(UUID reworkOrderId);
}
