package com.qualityos.organization.entity;

import com.qualityos.common.persistence.AuditedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One organisation using QualityOS. */
@Getter
@Setter
@Entity
@Table(name = "tenants")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tenant extends AuditedEntity {

    public enum Status { ACTIVE, SUSPENDED }

    private String name;
    private String legalName;
    private String gstin;
    private String industry;
    private String countryCode = "IN";
    private String timezone = "Asia/Kolkata";
    private String currency = "INR";
    private String locale = "en-IN";
    private String address;
    private Instant onboardedAt;

    @Enumerated(EnumType.STRING)
    private Status status = Status.ACTIVE;

    public Tenant(UUID id, String name) {
        setId(id);
        this.name = name;
    }
}
