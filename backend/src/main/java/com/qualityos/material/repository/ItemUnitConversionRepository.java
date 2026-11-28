package com.qualityos.material.repository;

import com.qualityos.material.entity.ItemUnitConversion;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemUnitConversionRepository extends JpaRepository<ItemUnitConversion, UUID> {

    List<ItemUnitConversion> findByItemIdOrderByCreatedAt(UUID itemId);
}
