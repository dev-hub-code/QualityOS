package com.qualityos.production.repository;

import com.qualityos.production.entity.Bom;
import com.qualityos.production.entity.RevisionedDefinition.Status;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface BomRepository extends JpaRepository<Bom, UUID>, JpaSpecificationExecutor<Bom> {

    List<Bom> findByProductIdAndKindOrderByRevisionDesc(UUID productId, Bom.Kind kind);

    List<Bom> findByProductIdAndKindAndStatus(UUID productId, Bom.Kind kind, Status status);

    boolean existsByKindAndStatusIn(Bom.Kind kind, Collection<Status> statuses);

    boolean existsByProductIdAndKindAndStatusIn(UUID productId, Bom.Kind kind, Collection<Status> statuses);

    @Query("SELECT coalesce(max(b.revision), 0) FROM Bom b WHERE b.productId = ?1 AND b.kind = ?2")
    int lastRevision(UUID productId, Bom.Kind kind);

    /** BOMs and recipes in effect for any of the products on the date. */
    @Query("""
            SELECT b FROM Bom b WHERE b.productId IN ?1 AND b.status = ?3 AND b.effectiveFrom <= ?2
            AND (b.effectiveTo IS NULL OR b.effectiveTo >= ?2)
            """)
    List<Bom> findApprovedOn(Collection<UUID> productIds, java.time.LocalDate on, Status approved);

    default List<Bom> findInEffect(Collection<UUID> productIds, java.time.LocalDate on) {
        return findApprovedOn(productIds, on, Status.APPROVED);
    }

    Optional<Bom> findFirstByProductIdAndKindAndStatusInOrderByRevisionDesc(
            UUID productId, Bom.Kind kind, Collection<Status> statuses);
}
