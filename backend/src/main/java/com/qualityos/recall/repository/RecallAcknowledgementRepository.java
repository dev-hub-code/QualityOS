package com.qualityos.recall.repository;

import com.qualityos.recall.entity.RecallAcknowledgement;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecallAcknowledgementRepository extends JpaRepository<RecallAcknowledgement, UUID> {

    List<RecallAcknowledgement> findByContactRecordIdInOrderByAcknowledgedOn(Collection<UUID> contactRecordIds);
}
