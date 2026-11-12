package com.qualityos.production.repository;

import com.qualityos.production.entity.ProcessParameter;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessParameterRepository extends JpaRepository<ProcessParameter, UUID> {

    List<ProcessParameter> findByBomIdOrderByCode(UUID bomId);

    List<ProcessParameter> findByOperationIdInOrderByCode(Collection<UUID> operationIds);

    boolean existsByBomIdAndCode(UUID bomId, String code);

    boolean existsByOperationIdAndCode(UUID operationId, String code);
}
