package com.qualityos.quality.repository;

import com.qualityos.quality.entity.CapaStatusChange;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CapaStatusChangeRepository extends JpaRepository<CapaStatusChange, UUID> {

    List<CapaStatusChange> findByCapaIdOrderByCreatedAt(UUID capaId);
}
