package com.qualityos.subscription.repository;

import com.qualityos.subscription.entity.PaymentTransaction;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    Optional<PaymentTransaction> findByProviderAndProviderReference(String provider, String reference);

    List<PaymentTransaction> findAllByOrderByOccurredAtDesc();
}
