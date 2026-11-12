package com.qualityos.quality.service;

import com.qualityos.common.attachment.AttachmentPolicy;
import com.qualityos.common.security.Permissions;
import com.qualityos.identity.service.FacilityScope;
import com.qualityos.quality.entity.CorrectiveAction;
import com.qualityos.quality.repository.CapaActionRepository;
import com.qualityos.quality.repository.CorrectiveActionRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Evidence that one action of a CAPA was done, kept with that action. Access follows the CAPA: whoever may see or
 * manage the CAPA may see or manage its actions' files, and they stop changing once the CAPA is finished.
 */
@Component
class CapaActionEvidence implements AttachmentPolicy {

    private final CapaActionRepository actions;
    private final CorrectiveActionRepository capas;
    private final FacilityScope scope;

    CapaActionEvidence(CapaActionRepository actions, CorrectiveActionRepository capas, FacilityScope scope) {
        this.actions = actions;
        this.capas = capas;
        this.scope = scope;
    }

    @Override
    public String entityType() {
        return "CAPA_ACTION";
    }

    @Override
    public String viewPermission() {
        return Permissions.CAPA_VIEW;
    }

    @Override
    public String managePermission() {
        return Permissions.CAPA_MANAGE;
    }

    @Override
    public boolean parentExists(UUID entityId) {
        return capaOf(entityId).filter(capa -> scope.allows(capa.getFacilityId())).isPresent();
    }

    @Override
    public boolean locked(UUID entityId) {
        return capaOf(entityId).map(capa -> capa.getStatus().isFinished()).orElse(false);
    }

    private Optional<CorrectiveAction> capaOf(UUID actionId) {
        return actions.findById(actionId).flatMap(action -> capas.findById(action.getCapaId()));
    }
}
