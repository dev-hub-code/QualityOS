package com.qualityos.traceability.repository;

import com.qualityos.traceability.entity.DispatchRecord;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DispatchRecordRepository extends JpaRepository<DispatchRecord, UUID> {
}
