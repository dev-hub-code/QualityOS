package com.qualityos.quality.service;

import com.qualityos.common.attachment.AttachmentPolicy;
import com.qualityos.common.security.Permissions;
import com.qualityos.common.tenant.TenantContext;
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
    private final com.qualityos.identity.service.TenantAccessService access;

    CapaActionEvidence(CapaActionRepository actions, CorrectiveActionRepository capas, FacilityScope scope,
            com.qualityos.identity.service.TenantAccessService access) {
        this.access = access;
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

    /** The action's owner sees its evidence, without needing to see every CAPA. */
    @Override
    public boolean callerMayView(UUID entityId) {
        UUID caller = TenantContext.getCurrentUserId();
        return actions.findById(entityId).filter(action -> action.getOwnerId().equals(caller)).isPresent();
    }

    /**
     * The action's owner may attach evidence to it, and take back a wrong file, until it is done, while the plan allows
     * CAPA work.
     */
    @Override
    public boolean callerMayManage(UUID entityId) {
        UUID caller = TenantContext.getCurrentUserId();
        return actions.findById(entityId)
                .filter(action -> !action.isDone() && action.getOwnerId().equals(caller))
                .isPresent() && access.planAllows(Permissions.CAPA_MANAGE);
    }

    @Override
    public boolean locked(UUID entityId) {
        return capaOf(entityId).map(capa -> capa.getStatus().isFinished()).orElse(false);
    }

    private Optional<CorrectiveAction> capaOf(UUID actionId) {
        return actions.findById(actionId).flatMap(action -> capas.findById(action.getCapaId()));
    }
}
