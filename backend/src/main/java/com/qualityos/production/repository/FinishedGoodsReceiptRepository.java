package com.qualityos.production.repository;

import com.qualityos.production.entity.FinishedGoodsReceipt;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FinishedGoodsReceiptRepository extends JpaRepository<FinishedGoodsReceipt, UUID> {

    Optional<FinishedGoodsReceipt> findByWorkOrderId(UUID workOrderId);

    boolean existsByWorkOrderId(UUID workOrderId);

    List<FinishedGoodsReceipt> findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(Instant from, Instant to);
}
