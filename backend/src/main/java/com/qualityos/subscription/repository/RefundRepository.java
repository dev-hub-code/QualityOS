package com.qualityos.subscription.repository;

import com.qualityos.subscription.entity.Refund;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    List<Refund> findByCreditNoteIdIn(Collection<UUID> creditNoteIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Refund> findByProviderAndRefundReference(String provider, String refundReference);

    boolean existsByProviderAndRefundReferenceIgnoreCase(String provider, String refundReference);

    @org.springframework.data.jpa.repository.Query(
            "select r.id from Refund r where r.provider = ?1 and r.refundReference = ?2")
    Optional<UUID> findIdByProviderAndRefundReference(String provider, String refundReference);

    @org.springframework.data.jpa.repository.Query("select r.id from Refund r where r.provider = ?1 and r.status = ?2"
            + " and r.createdAt < ?3 order by r.createdAt")
    List<UUID> findIdsWaiting(String provider, Refund.Status status, java.time.Instant askedBefore);
}
