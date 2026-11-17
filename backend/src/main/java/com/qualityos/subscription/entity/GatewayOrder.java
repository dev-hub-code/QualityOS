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
     * CREATING: reserved for the invoice, while Cashfree is asked to create it. NEEDS_REVIEW: money was taken but could
     * not be applied, so a person must decide what happens to it. ABANDONED: Cashfree has no such order, or it expired
     * there unpaid; a payment that still reaches it is applied all the same.
     */
    public enum Status { CREATING, ACTIVE, PAID, NEEDS_REVIEW, ABANDONED }

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
