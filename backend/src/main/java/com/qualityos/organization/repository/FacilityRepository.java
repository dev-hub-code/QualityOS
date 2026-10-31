package com.qualityos.organization.repository;

import com.qualityos.organization.entity.Facility;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface FacilityRepository extends JpaRepository<Facility, UUID>, JpaSpecificationExecutor<Facility> {

    boolean existsByCode(String code);
}
