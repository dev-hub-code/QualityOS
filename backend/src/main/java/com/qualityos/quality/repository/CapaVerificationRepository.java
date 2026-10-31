package com.qualityos.quality.repository;

import com.qualityos.quality.entity.CapaVerification;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CapaVerificationRepository extends JpaRepository<CapaVerification, UUID> {

    List<CapaVerification> findByCapaIdOrderByCreatedAt(UUID capaId);
}
