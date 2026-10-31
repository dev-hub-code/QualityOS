package com.qualityos.quality.repository;

import com.qualityos.quality.entity.CapaAction;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CapaActionRepository extends JpaRepository<CapaAction, UUID> {

    List<CapaAction> findByCapaIdOrderByCreatedAt(UUID capaId);

    Optional<CapaAction> findByIdAndCapaId(UUID id, UUID capaId);
}
