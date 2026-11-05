package com.qualityos.quality.service;

import com.qualityos.organization.service.SeverityThreshold;
import com.qualityos.organization.service.TenantSettingsService;
import com.qualityos.quality.entity.NonConformance.Severity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The organisation's NCR and CAPA rules, read from its settings each time so that a change applies at once. */
@Service
class QualityRules {

    private final TenantSettingsService settings;

    QualityRules(TenantSettingsService settings) {
        this.settings = settings;
    }

    /** An NCR of this severity needs a closed CAPA before verification can be requested. */
    @Transactional(readOnly = true)
    boolean capaRequired(Severity severity) {
        return covers(settings.get().capaRequiredFrom(), severity);
    }

    /** A CAPA raised on an NCR of this severity must have its fix verified as effective before it closes. */
    @Transactional(readOnly = true)
    boolean effectivenessRequired(Severity severity) {
        return covers(settings.get().effectivenessRequiredFrom(), severity);
    }

    static boolean covers(SeverityThreshold threshold, Severity severity) {
        return switch (threshold) {
            case NEVER -> false;
            case MINOR -> true;
            case MAJOR -> severity != Severity.MINOR;
            case CRITICAL -> severity == Severity.CRITICAL;
        };
    }
}
