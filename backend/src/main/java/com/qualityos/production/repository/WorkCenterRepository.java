package com.qualityos.production.repository;

import com.qualityos.production.entity.WorkCenter;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface WorkCenterRepository extends JpaRepository<WorkCenter, UUID>, JpaSpecificationExecutor<WorkCenter> {

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, UUID id);
}
