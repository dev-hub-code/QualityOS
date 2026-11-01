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

    /** NEEDS_REVIEW: money was taken but could not be applied, so a person must decide what happens to it. */
    public enum Status { ACTIVE, PAID, NEEDS_REVIEW }

    private UUID invoiceId;
    private String provider;
    private String gatewayOrderId;
    private BigDecimal amount;
    private Instant expiresAt;

    @Setter
    @Enumerated(EnumType.STRING)
    private Status status = Status.ACTIVE;

    public GatewayOrder(UUID invoiceId, String provider, String gatewayOrderId, BigDecimal amount, Instant expiresAt) {
        this.invoiceId = invoiceId;
        this.provider = provider;
        this.gatewayOrderId = gatewayOrderId;
        this.amount = amount;
        this.expiresAt = expiresAt;
    }
}
