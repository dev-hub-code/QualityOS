package com.qualityos.subscription.repository;

import com.qualityos.subscription.entity.SubscriptionInvoice;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionInvoiceRepository extends JpaRepository<SubscriptionInvoice, UUID> {

    List<SubscriptionInvoice> findAllByOrderByCreatedAtDesc();

    Optional<SubscriptionInvoice> findByStatus(SubscriptionInvoice.Status status);

    List<SubscriptionInvoice> findByStatusAndPeriodEndsAtAfter(SubscriptionInvoice.Status status, java.time.Instant after);

    boolean existsByRenewsPeriodEnding(java.time.Instant periodEnding);

    Optional<SubscriptionInvoice> findFirstByStatusAndPlanIdAndBillingCycleOrderByPaidAtDesc(
            SubscriptionInvoice.Status status, UUID planId, com.qualityos.subscription.entity.Subscription.Cycle cycle);
}
