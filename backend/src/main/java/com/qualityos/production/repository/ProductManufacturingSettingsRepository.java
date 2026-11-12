package com.qualityos.production.repository;

import com.qualityos.production.entity.ProductManufacturingSettings;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductManufacturingSettingsRepository extends JpaRepository<ProductManufacturingSettings, UUID> {

    Optional<ProductManufacturingSettings> findByProductId(UUID productId);
}
