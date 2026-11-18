package com.qualityos.traceability.entity;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Stock sent from one plant to another. It is in transit until the other plant receives it. */
@Getter
@Entity
@Table(name = "stock_transfers")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockTransfer extends TenantOwnedEntity {

    public enum Status { IN_TRANSIT, RECEIVED, CANCELLED }

    private String transferNumber;
    private UUID fromFacilityId;
    private UUID toFacilityId;

    @Enumerated(EnumType.STRING)
    private Status status = Status.IN_TRANSIT;

    private LocalDate sentOn;
    private LocalDate receivedOn;
    private UUID receivedBy;
    private String note;
    private String cancelReason;

    public StockTransfer(String transferNumber, UUID fromFacilityId, UUID toFacilityId, LocalDate sentOn, String note) {
        if (fromFacilityId.equals(toFacilityId)) {
            throw new BusinessRuleException("A transfer goes to another plant.");
        }
        this.transferNumber = transferNumber;
        this.fromFacilityId = fromFacilityId;
        this.toFacilityId = toFacilityId;
        this.sentOn = sentOn;
        this.note = note;
    }

    public void receive(LocalDate on, UUID by) {
        requireInTransit();
        if (on.isBefore(sentOn)) {
            throw new BusinessRuleException("Goods cannot arrive before they were sent, on " + sentOn + ".");
        }
        status = Status.RECEIVED;
        receivedOn = on;
        receivedBy = by;
    }

    public void cancel(String reason) {
        requireInTransit();
        status = Status.CANCELLED;
        cancelReason = reason;
    }

    private void requireInTransit() {
        if (status != Status.IN_TRANSIT) {
            throw new BusinessRuleException("Transfer " + transferNumber + " is no longer in transit.");
        }
    }
}
