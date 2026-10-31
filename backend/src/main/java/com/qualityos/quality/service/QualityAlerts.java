package com.qualityos.quality.service;

import com.qualityos.common.security.Permissions;
import com.qualityos.identity.service.TenantAccessService;
import com.qualityos.notification.service.NotificationService;
import com.qualityos.notification.service.NotificationType;
import com.qualityos.quality.entity.CapaAction;
import com.qualityos.quality.entity.CorrectiveAction;
import com.qualityos.quality.entity.NonConformance;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Says who hears about each NCR and CAPA event. Runs in the event's transaction. */
@Component
class QualityAlerts {

    private final NotificationService notifications;
    private final TenantAccessService access;

    QualityAlerts(NotificationService notifications, TenantAccessService access) {
        this.notifications = notifications;
        this.access = access;
    }

    void ncrRaised(NonConformance ncr) {
        ncr(access.holdersOf(Permissions.NCR_ASSIGN), NotificationType.NCR_RAISED, ncr,
                ncr.getNcrNumber() + " was raised", ncr.getSeverity() + " non-conformance: " + ncr.getTitle());
    }

    void ncrAssigned(NonConformance ncr) {
        ncr(Set.of(ncr.getAssignedTo()), NotificationType.NCR_ASSIGNED, ncr,
                ncr.getNcrNumber() + " was assigned to you", ncr.getTitle());
    }

    void ncrAwaitingClosure(NonConformance ncr) {
        ncr(access.holdersOf(Permissions.NCR_CLOSE), NotificationType.NCR_AWAITING_CLOSURE, ncr,
                ncr.getNcrNumber() + " is ready for closure", ncr.getTitle());
    }

    void capaAssigned(CorrectiveAction capa) {
        capa(Set.of(capa.getOwnerId()), NotificationType.CAPA_ASSIGNED, capa,
                capa.getCapaNumber() + " was assigned to you",
                capa.getTitle() + ". Due " + capa.getTargetDate() + ".");
    }

    void actionAssigned(CorrectiveAction capa, CapaAction action) {
        capa(Set.of(action.getOwnerId()), NotificationType.CAPA_ACTION_ASSIGNED, capa,
                "New action on " + capa.getCapaNumber(),
                action.getDescription() + ". Due " + action.getTargetDate() + ".");
    }

    void capaAwaitingVerification(CorrectiveAction capa) {
        capa(access.holdersOf(Permissions.CAPA_APPROVE), NotificationType.CAPA_AWAITING_VERIFICATION, capa,
                capa.getCapaNumber() + " needs effectiveness verification", capa.getTitle());
    }

    void capaOverdue(CorrectiveAction capa) {
        capa(Set.of(capa.getOwnerId()), NotificationType.CAPA_OVERDUE, capa,
                capa.getCapaNumber() + " is overdue",
                capa.getTitle() + " was due on " + capa.getTargetDate() + ".");
    }

    private void ncr(Set<UUID> to, NotificationType type, NonConformance ncr, String title, String body) {
        notifications.notify(to, type, title, body, "/ncrs/" + ncr.getId());
    }

    private void capa(Set<UUID> to, NotificationType type, CorrectiveAction capa, String title, String body) {
        notifications.notify(to, type, title, body, "/capas/" + capa.getId());
    }
}
