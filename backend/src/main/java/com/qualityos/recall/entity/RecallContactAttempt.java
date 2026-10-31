package com.qualityos.recall.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "recall_contact_attempts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecallContactAttempt extends TenantOwnedEntity {

    public enum Method { EMAIL, PHONE, WHATSAPP, OTHER }

    /** A sent email is {@code NO_REPLY}: it only counts as reaching someone once they answer. */
    public enum Outcome { NO_REPLY, REACHED, FAILED }

    private UUID contactRecordId;

    @Enumerated(EnumType.STRING)
    private Method method;

    private String contactPerson;
    private String contactDetails;
    private Instant attemptedAt;

    @Enumerated(EnumType.STRING)
    private Outcome outcome;

    private String customerResponse;
    private String notes;

    public RecallContactAttempt(
            UUID contactRecordId,
            Method method,
            String contactPerson,
            String contactDetails,
            Instant attemptedAt,
            Outcome outcome,
            String customerResponse,
            String notes) {
        this.contactRecordId = contactRecordId;
        this.method = method;
        this.contactPerson = contactPerson;
        this.contactDetails = contactDetails;
        this.attemptedAt = attemptedAt;
        this.outcome = outcome;
        this.customerResponse = customerResponse;
        this.notes = notes;
    }
}
