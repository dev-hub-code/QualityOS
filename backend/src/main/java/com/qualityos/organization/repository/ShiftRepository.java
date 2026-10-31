package com.qualityos.organization.repository;

import com.qualityos.organization.entity.Shift;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShiftRepository extends JpaRepository<Shift, UUID> {

    List<Shift> findByFacilityIdAndArchivedAtIsNullOrderByStartsAt(UUID facilityId);

    boolean existsByFacilityIdAndName(UUID facilityId, String name);
}
