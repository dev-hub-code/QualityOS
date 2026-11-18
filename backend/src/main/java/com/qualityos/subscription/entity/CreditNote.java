package com.qualityos.subscription.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Takes back part or all of a paid invoice, with its GST. A document of record: the database refuses any change. */
@Getter
@Entity
@Table(name = "credit_notes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreditNote extends TenantOwnedEntity {

    private String creditNoteNumber;
    private UUID invoiceId;
    private BigDecimal amount;
    private BigDecimal taxAmount;
    private BigDecimal cgstAmount;
    private BigDecimal sgstAmount;
    private BigDecimal igstAmount;
    private BigDecimal total;
    private String reason;
    private LocalDate issuedOn;
    private boolean endsAccess;

    @SuppressWarnings("java:S107")
    public CreditNote(String creditNoteNumber, UUID invoiceId, BigDecimal amount, BigDecimal taxAmount,
            BigDecimal cgstAmount, BigDecimal sgstAmount, BigDecimal igstAmount, String reason, LocalDate issuedOn,
            boolean endsAccess) {
        this.creditNoteNumber = creditNoteNumber;
        this.invoiceId = invoiceId;
        this.amount = amount;
        this.taxAmount = taxAmount;
        this.cgstAmount = cgstAmount;
        this.sgstAmount = sgstAmount;
        this.igstAmount = igstAmount;
        this.total = amount.add(taxAmount);
        this.reason = reason;
        this.issuedOn = issuedOn;
        this.endsAccess = endsAccess;
    }
}
