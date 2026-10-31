package com.qualityos.subscription.repository;

import com.qualityos.subscription.entity.SubscriptionInvoice;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionInvoiceRepository extends JpaRepository<SubscriptionInvoice, UUID> {

    List<SubscriptionInvoice> findAllByOrderByCreatedAtDesc();

    Optional<SubscriptionInvoice> findByStatus(SubscriptionInvoice.Status status);
}
