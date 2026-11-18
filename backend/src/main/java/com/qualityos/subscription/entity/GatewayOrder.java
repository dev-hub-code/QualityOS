package com.qualityos.subscription.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Entity
@Table(name = "gateway_orders")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GatewayOrder extends TenantOwnedEntity {

    /**
     * CREATING: reserved for the invoice, while Razorpay is asked to create it. NEEDS_REVIEW: money was taken but could
     * not be applied, so a person must decide what happens to it. ABANDONED: Razorpay has no such order, or checkout
     * moved on to a newer one; a payment that still reaches it is applied all the same. REFUND_PENDING: a person chose to refund
     * a NEEDS_REVIEW payment, and Razorpay has not yet said it went through; it becomes REFUNDED when it does, and
     * goes back to NEEDS_REVIEW when it fails.
     */
    public enum Status { CREATING, ACTIVE, PAID, NEEDS_REVIEW, ABANDONED, REFUND_PENDING, REFUNDED }

    private UUID invoiceId;
    private String provider;
    private String gatewayOrderId;
    private BigDecimal amount;
    private Instant expiresAt;

    @Setter
    @Enumerated(EnumType.STRING)
    private Status status = Status.CREATING;

    public GatewayOrder(UUID invoiceId, String provider, String gatewayOrderId, BigDecimal amount, Instant expiresAt) {
        this.invoiceId = invoiceId;
        this.provider = provider;
        this.gatewayOrderId = gatewayOrderId;
        this.amount = amount;
        this.expiresAt = expiresAt;
    }
}
