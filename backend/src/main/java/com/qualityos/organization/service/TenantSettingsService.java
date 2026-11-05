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
    public record Settings(boolean singlePersonReview, Long version) {
    }

    private final TenantSettingsRepository settings;
    private final AuditService audit;

    TenantSettingsService(TenantSettingsRepository settings, AuditService audit) {
        this.settings = settings;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Settings get() {
        return settings.findFirstBy().map(TenantSettingsService::view).orElse(new Settings(false, null));
    }

    /** The first save creates the row. A stale version, including null after someone else saved, is refused. */
    @Transactional
    public Settings update(boolean singlePersonReview, Long version) {
        TenantSettings row = settings.findFirstBy().orElseGet(TenantSettings::new);
        row.requireVersion(version);
        boolean previous = row.isSinglePersonReview();
        row.setSinglePersonReview(singlePersonReview);
        TenantSettings saved = settings.saveAndFlush(row);
        audit.record("TENANT_SETTINGS_UPDATED", "Organization", TenantContext.getCurrentTenantId(),
                Map.of("singlePersonReview", previous), Map.of("singlePersonReview", singlePersonReview));
        return view(saved);
    }

    private static Settings view(TenantSettings row) {
        return new Settings(row.isSinglePersonReview(), row.getVersion());
    }
}
