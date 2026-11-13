package com.qualityos.production.repository;

import com.qualityos.production.entity.ReworkOrderStep;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReworkOrderStepRepository extends JpaRepository<ReworkOrderStep, UUID> {

    List<ReworkOrderStep> findByReworkOrderIdOrderBySequence(UUID reworkOrderId);
}
