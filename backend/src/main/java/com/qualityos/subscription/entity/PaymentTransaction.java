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

@Getter
@Entity
@Table(name = "payment_transactions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentTransaction extends TenantOwnedEntity {

    public enum Status { SUCCEEDED, FAILED }

    private UUID invoiceId;
    private String provider;
    private String providerReference;
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private Status status;

    private String failureReason;
    private Instant occurredAt;

    public PaymentTransaction(
            UUID invoiceId, String provider, String reference, BigDecimal amount, Status status, String failureReason) {
        this.invoiceId = invoiceId;
        this.provider = provider;
        this.providerReference = reference;
        this.amount = amount;
        this.status = status;
        this.failureReason = failureReason;
        this.occurredAt = Instant.now();
    }
}
