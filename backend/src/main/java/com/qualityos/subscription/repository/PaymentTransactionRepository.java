package com.qualityos.subscription.repository;

import com.qualityos.subscription.entity.PaymentTransaction;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    /** Matches the unique index: one reference per platform, whatever the provider or letter case. */
    Optional<PaymentTransaction> findByProviderReferenceIgnoreCase(String reference);

    List<PaymentTransaction> findAllByOrderByOccurredAtDesc();
}
