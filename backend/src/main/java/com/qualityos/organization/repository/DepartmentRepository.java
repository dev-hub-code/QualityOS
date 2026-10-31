package com.qualityos.organization.repository;

import com.qualityos.organization.entity.Department;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentRepository extends JpaRepository<Department, UUID> {

    List<Department> findByFacilityIdAndArchivedAtIsNullOrderByName(UUID facilityId);

    boolean existsByFacilityIdAndCode(UUID facilityId, String code);
}
