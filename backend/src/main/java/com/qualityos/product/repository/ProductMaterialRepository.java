package com.qualityos.product.repository;

import com.qualityos.product.entity.ProductMaterial;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductMaterialRepository extends JpaRepository<ProductMaterial, UUID> {

    List<ProductMaterial> findByProductId(UUID productId);

    boolean existsByProductIdAndMaterialId(UUID productId, UUID materialId);
}
