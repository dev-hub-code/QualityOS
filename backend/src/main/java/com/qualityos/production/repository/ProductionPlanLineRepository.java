package com.qualityos.production.repository;

import com.qualityos.production.entity.ProductionPlanLine;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductionPlanLineRepository extends JpaRepository<ProductionPlanLine, UUID> {

    List<ProductionPlanLine> findByPlanIdOrderByDueDateAscIdAsc(UUID planId);

    List<ProductionPlanLine> findByIdIn(Collection<UUID> ids);
}
