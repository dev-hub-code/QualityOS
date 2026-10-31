package com.qualityos.subscription.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "subscription_invoices")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubscriptionInvoice extends TenantOwnedEntity {

    public enum Status { ISSUED, PAID, VOID }

    private UUID subscriptionId;
    private String invoiceNumber;
    private UUID planId;
    private String planName;

    @Enumerated(EnumType.STRING)
    private Subscription.Cycle billingCycle;

    private BigDecimal amount;
    private BigDecimal taxPercent;
    private BigDecimal taxAmount;
    private BigDecimal total;

    @Enumerated(EnumType.STRING)
    private Status status = Status.ISSUED;

    private String buyerName;
    private String buyerGstin;
    private String buyerAddress;
    private LocalDate issuedOn;
    private LocalDate dueOn;
    private Instant paidAt;
    private Instant periodStartsAt;
    private Instant periodEndsAt;

    public SubscriptionInvoice(UUID subscriptionId, String invoiceNumber) {
        this.subscriptionId = subscriptionId;
        this.invoiceNumber = invoiceNumber;
    }
}
