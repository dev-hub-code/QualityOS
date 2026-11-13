package com.qualityos.production.repository;

import com.qualityos.production.entity.RevisionedDefinition.Status;
import com.qualityos.production.entity.Routing;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface RoutingRepository extends JpaRepository<Routing, UUID>, JpaSpecificationExecutor<Routing> {

    List<Routing> findByProductIdOrderByRevisionDesc(UUID productId);

    List<Routing> findByProductIdAndStatus(UUID productId, Status status);

    boolean existsByProductIdAndStatusIn(UUID productId, Collection<Status> statuses);

    @Query("SELECT coalesce(max(r.revision), 0) FROM Routing r WHERE r.productId = ?1")
    int lastRevision(UUID productId);

    /** The routing in effect for the product on the date. */
    @Query("""
            SELECT r FROM Routing r WHERE r.productId = ?1 AND r.status = ?3 AND r.effectiveFrom <= ?2
            AND (r.effectiveTo IS NULL OR r.effectiveTo >= ?2)
            """)
    List<Routing> findApprovedOn(UUID productId, java.time.LocalDate on, Status approved);
}
