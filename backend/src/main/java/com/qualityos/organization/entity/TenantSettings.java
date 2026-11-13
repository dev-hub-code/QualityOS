package com.qualityos.organization.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import com.qualityos.organization.service.SeverityThreshold;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The working rules one organisation has chosen. Absent until first saved, when every rule has its default. */
@Getter
@Setter
@Entity
@Table(name = "tenant_settings")
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class TenantSettings extends TenantOwnedEntity {

    /** A sole member may review their own work. Has no effect once a second member is active. */
    private boolean singlePersonReview;

    /** NCRs at least this severe need a closed CAPA before verification. */
    @Enumerated(EnumType.STRING)
    private SeverityThreshold capaRequiredFrom = SeverityThreshold.MAJOR;

    /** CAPAs raised on NCRs at least this severe must have their fix verified as effective. */
    @Enumerated(EnumType.STRING)
    private SeverityThreshold effectivenessRequiredFrom = SeverityThreshold.MAJOR;
}
