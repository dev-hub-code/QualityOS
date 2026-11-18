package com.qualityos.traceability.repository;

import com.qualityos.traceability.entity.StockTransferItem;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockTransferItemRepository extends JpaRepository<StockTransferItem, UUID> {

    List<StockTransferItem> findByTransferIdInOrderByCreatedAt(Collection<UUID> transferIds);
}
