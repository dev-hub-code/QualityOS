package com.qualityos.recall.repository;

import com.qualityos.recall.entity.RecallContactAttempt;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecallContactAttemptRepository extends JpaRepository<RecallContactAttempt, UUID> {

    List<RecallContactAttempt> findByContactRecordIdInOrderByAttemptedAt(Collection<UUID> contactRecordIds);
}
