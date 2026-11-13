package com.qualityos.production.repository;

import com.qualityos.production.entity.ProductionPlan;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ProductionPlanRepository
        extends JpaRepository<ProductionPlan, UUID>, JpaSpecificationExecutor<ProductionPlan> {
}
