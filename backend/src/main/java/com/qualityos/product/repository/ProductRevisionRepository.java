package com.qualityos.product.repository;

import com.qualityos.product.entity.ProductRevision;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRevisionRepository extends JpaRepository<ProductRevision, UUID> {

    List<ProductRevision> findByProductIdOrderByCreatedAtDesc(UUID productId);

    boolean existsByProductIdAndRevision(UUID productId, String revision);
}
