package com.qualityos.subscription.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Money going back: for a credit note, or for a payment the gateway took that no invoice could take. Through the
 * gateway it waits until the gateway says it went through; by bank it is recorded once made.
 */
@Getter
@Entity
@Table(name = "refunds")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refund extends TenantOwnedEntity {

    public enum Status { PENDING, SUCCEEDED, FAILED }

    public static final String GATEWAY = "CASHFREE";
    public static final String MANUAL = "MANUAL";

    private UUID creditNoteId;
    private UUID gatewayOrderId;
    private String provider;
    /** Ours, sent to the gateway as its refund id; or the bank's reference for a refund made by hand. */
    private String refundReference;
    private String gatewayRefundId;
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    private String failureReason;
    private LocalDate refundedOn;

    public static Refund throughGateway(UUID creditNoteId, UUID gatewayOrderId, BigDecimal amount) {
        Refund refund = new Refund();
        refund.creditNoteId = creditNoteId;
        refund.gatewayOrderId = creditNoteId == null ? gatewayOrderId : null;
        refund.provider = GATEWAY;
        refund.refundReference = "RF" + refund.getId().toString().replace("-", "");
        refund.amount = amount;
        return refund;
    }

    public static Refund byHand(UUID creditNoteId, String bankReference, BigDecimal amount, LocalDate on) {
        Refund refund = new Refund();
        refund.creditNoteId = creditNoteId;
        refund.provider = MANUAL;
        refund.refundReference = bankReference;
        refund.amount = amount;
        refund.status = Status.SUCCEEDED;
        refund.refundedOn = on;
        return refund;
    }

    /** What the gateway last said. A refund that went through or failed stays that way. */
    public boolean update(String gatewayStatus, String gatewayRefundId, String message, LocalDate today) {
        if (status != Status.PENDING) {
            return false;
        }
        if (gatewayRefundId != null) {
            this.gatewayRefundId = gatewayRefundId;
        }
        switch (gatewayStatus == null ? "" : gatewayStatus) {
            case "SUCCESS" -> {
                status = Status.SUCCEEDED;
                refundedOn = today;
                return true;
            }
            case "CANCELLED", "FAILED" -> {
                status = Status.FAILED;
                failureReason = message == null ? "The gateway did not complete the refund." : message;
                return true;
            }
            default -> {
                return false;
            }
        }
    }
}
