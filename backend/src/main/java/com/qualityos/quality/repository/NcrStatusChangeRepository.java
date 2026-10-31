package com.qualityos.quality.repository;

import com.qualityos.quality.entity.NcrStatusChange;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NcrStatusChangeRepository extends JpaRepository<NcrStatusChange, UUID> {

    List<NcrStatusChange> findByNcrIdOrderByCreatedAt(UUID ncrId);
}
