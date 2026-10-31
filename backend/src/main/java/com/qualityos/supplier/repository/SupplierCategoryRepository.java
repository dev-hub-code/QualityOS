package com.qualityos.supplier.repository;

import com.qualityos.supplier.entity.SupplierCategory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierCategoryRepository extends JpaRepository<SupplierCategory, UUID> {

    List<SupplierCategory> findAllByOrderByName();

    boolean existsByName(String name);
}
