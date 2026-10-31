package com.qualityos.traceability.repository;

import com.qualityos.traceability.entity.DispatchRecord;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
public interface DispatchRecordRepository extends JpaRepository<DispatchRecord, UUID> {

    /** Serialises cancelling, shipping and returning, which rule each other out. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DispatchRecord d where d.id = :id")
    Optional<DispatchRecord> findForUpdate(UUID id);
}
