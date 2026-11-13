package com.qualityos.organization.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.organization.entity.TenantSettings;
import com.qualityos.organization.repository.TenantSettingsRepository;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The current organisation's working rules. */
@Service
public class TenantSettingsService {

    /** @param version null until the organisation first saves its settings */
    public record Settings(
            boolean singlePersonReview,
            SeverityThreshold capaRequiredFrom,
            SeverityThreshold effectivenessRequiredFrom,
            Long version) {

        static final Settings DEFAULTS = view(new TenantSettings());
    }

    private final TenantSettingsRepository settings;
    private final AuditService audit;

    TenantSettingsService(TenantSettingsRepository settings, AuditService audit) {
        this.settings = settings;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Settings get() {
        return settings.findFirstBy().map(TenantSettingsService::view).orElse(Settings.DEFAULTS);
    }

    /**
     * Replaces every rule at once. The first save creates the row. A stale version, including null after someone else
     * saved, is refused.
     */
    @Transactional
    public Settings update(Settings desired) {
        TenantSettings row = settings.findFirstBy().orElseGet(TenantSettings::new);
        row.requireVersion(desired.version());
        Map<String, Object> previous = snapshot(view(row));
        row.setSinglePersonReview(desired.singlePersonReview());
        row.setCapaRequiredFrom(desired.capaRequiredFrom());
        row.setEffectivenessRequiredFrom(desired.effectivenessRequiredFrom());
        Settings saved = view(settings.saveAndFlush(row));
        audit.record("TENANT_SETTINGS_UPDATED", "Organization", TenantContext.getCurrentTenantId(),
                previous, snapshot(saved));
        return saved;
    }

    private static Settings view(TenantSettings row) {
        return new Settings(row.isSinglePersonReview(), row.getCapaRequiredFrom(), row.getEffectivenessRequiredFrom(),
                row.getVersion());
    }

    private static Map<String, Object> snapshot(Settings settings) {
        return Map.of(
                "singlePersonReview", settings.singlePersonReview(),
                "capaRequiredFrom", settings.capaRequiredFrom(),
                "effectivenessRequiredFrom", settings.effectivenessRequiredFrom());
    }
}
