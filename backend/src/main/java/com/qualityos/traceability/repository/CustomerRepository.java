package com.qualityos.traceability.repository;

import com.qualityos.traceability.entity.Customer;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    List<Customer> findByArchivedAtIsNullOrderByCode();

    boolean existsByCode(String code);
}
