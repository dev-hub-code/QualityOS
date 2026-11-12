package com.qualityos.production.repository;

import com.qualityos.production.entity.BomLine;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BomLineRepository extends JpaRepository<BomLine, UUID> {

    List<BomLine> findByBomIdOrderByLineNumber(UUID bomId);

    List<BomLine> findByBomIdIn(Collection<UUID> bomIds);
}
