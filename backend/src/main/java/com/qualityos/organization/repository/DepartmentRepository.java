package com.qualityos.organization.repository;

import com.qualityos.organization.entity.Department;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentRepository extends JpaRepository<Department, UUID> {

    List<Department> findByFacilityIdAndArchivedAtIsNullOrderByName(UUID facilityId);

    List<Department> findByFacilityIdInAndArchivedAtIsNullOrderByName(Collection<UUID> facilityIds);

    boolean existsByFacilityIdAndCodeIgnoreCase(UUID facilityId, String code);
}
