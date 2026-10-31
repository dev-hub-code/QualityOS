package com.qualityos.organization.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A shift may cross midnight, so {@code endsAt} can be earlier than {@code startsAt}. */
@Getter
@Entity
@Table(name = "shifts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Shift extends TenantOwnedEntity {

    private UUID facilityId;
    private String name;
    private LocalTime startsAt;
    private LocalTime endsAt;

    @Setter
    private Instant archivedAt;

    public Shift(UUID facilityId, String name, LocalTime startsAt, LocalTime endsAt) {
        this.facilityId = facilityId;
        this.name = name;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
    }
}
