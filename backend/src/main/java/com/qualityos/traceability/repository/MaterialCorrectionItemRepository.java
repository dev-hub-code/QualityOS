package com.qualityos.traceability.repository;

import com.qualityos.traceability.entity.MaterialCorrectionItem;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaterialCorrectionItemRepository extends JpaRepository<MaterialCorrectionItem, UUID> {

    List<MaterialCorrectionItem> findByCorrectionIdIn(Collection<UUID> correctionIds);
}
