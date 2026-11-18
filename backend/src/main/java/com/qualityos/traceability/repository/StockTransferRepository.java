package com.qualityos.traceability.repository;

import com.qualityos.traceability.entity.StockTransfer;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface StockTransferRepository extends JpaRepository<StockTransfer, UUID>,
        JpaSpecificationExecutor<StockTransfer> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from StockTransfer t where t.id = :id")
    Optional<StockTransfer> findForUpdate(UUID id);
}
