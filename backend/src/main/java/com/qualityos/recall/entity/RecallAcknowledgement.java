package com.qualityos.recall.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "recall_acknowledgements")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecallAcknowledgement extends TenantOwnedEntity {

    private UUID contactRecordId;
    private LocalDate acknowledgedOn;
    private String acknowledgedBy;
    private String customerResponse;

    public RecallAcknowledgement(
            UUID contactRecordId, LocalDate acknowledgedOn, String acknowledgedBy, String customerResponse) {
        this.contactRecordId = contactRecordId;
        this.acknowledgedOn = acknowledgedOn;
        this.acknowledgedBy = acknowledgedBy;
        this.customerResponse = customerResponse;
    }
}
