package com.qualityos.subscription.repository;

import com.qualityos.subscription.entity.GatewayOrder;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface GatewayOrderRepository extends JpaRepository<GatewayOrder, UUID> {

    Optional<GatewayOrder> findByProviderAndGatewayOrderId(String provider, String gatewayOrderId);

    /** Held while settling, so that a webhook and the customer's return cannot both apply one payment. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from GatewayOrder o where o.provider = ?1 and o.gatewayOrderId = ?2")
    Optional<GatewayOrder> lockByProviderAndGatewayOrderId(String provider, String gatewayOrderId);

    Optional<GatewayOrder> findFirstByInvoiceIdAndStatusAndExpiresAtAfterOrderByExpiresAtDesc(
            UUID invoiceId, GatewayOrder.Status status, Instant now);
}
