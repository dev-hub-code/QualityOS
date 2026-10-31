package com.qualityos.materialreceipt.repository;

import com.qualityos.materialreceipt.entity.MaterialReceiptItem;
import com.qualityos.materialreceipt.entity.MaterialReceiptItem.InspectionStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MaterialReceiptItemRepository extends JpaRepository<MaterialReceiptItem, UUID> {

    @EntityGraph(attributePaths = "receipt")
    Page<MaterialReceiptItem> findByInspectionStatus(InspectionStatus status, Pageable pageable);

    @EntityGraph(attributePaths = "receipt")
    @Query("select i from MaterialReceiptItem i where i.id = :id")
    Optional<MaterialReceiptItem> findWithReceipt(UUID id);
}
