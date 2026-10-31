package com.qualityos.recall.repository;

import com.qualityos.recall.entity.RecallContact;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecallContactRepository extends JpaRepository<RecallContact, UUID> {

    List<RecallContact> findByRecallId(UUID recallId);

    Optional<RecallContact> findByRecallIdAndCustomerId(UUID recallId, UUID customerId);
}
