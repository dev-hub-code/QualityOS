package com.qualityos.production.repository;

import com.qualityos.production.entity.MaterialReservation;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MaterialReservationRepository extends JpaRepository<MaterialReservation, UUID> {

    List<MaterialReservation> findByWorkOrderIdOrderByCreatedAtAscIdAsc(UUID workOrderId);

    @Query("""
            SELECT r FROM MaterialReservation r WHERE r.workOrderId = ?1 AND r.openQuantity > 0
            ORDER BY r.createdAt, r.id
            """)
    List<MaterialReservation> findOpen(UUID workOrderId);
}
