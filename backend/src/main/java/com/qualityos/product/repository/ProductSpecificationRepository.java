package com.qualityos.product.repository;

import com.qualityos.product.entity.ProductSpecification;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductSpecificationRepository extends JpaRepository<ProductSpecification, UUID> {

    List<ProductSpecification> findByProductIdOrderBySortOrderAscNameAsc(UUID productId);
}
