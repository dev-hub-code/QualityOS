package com.qualityos.materialreceipt.repository;

import com.qualityos.materialreceipt.entity.MaterialReceipt;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface MaterialReceiptRepository
        extends JpaRepository<MaterialReceipt, UUID>, JpaSpecificationExecutor<MaterialReceipt> {

    @EntityGraph(attributePaths = "items")
    @Query("select r from MaterialReceipt r where r.id = :id")
    Optional<MaterialReceipt> findWithItems(UUID id);
}
