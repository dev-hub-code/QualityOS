package com.qualityos.supplier.repository;

import com.qualityos.supplier.entity.SupplierContact;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierContactRepository extends JpaRepository<SupplierContact, UUID> {

    List<SupplierContact> findBySupplierIdOrderByPrimaryDescName(UUID supplierId);
}
