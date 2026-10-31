package com.qualityos.inspection.service;

import com.qualityos.common.security.Permissions;
import com.qualityos.identity.service.TenantAccessService;
import com.qualityos.inspection.entity.Inspection;
import com.qualityos.notification.service.NotificationService;
import com.qualityos.notification.service.NotificationType;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Says who hears about each inspection event, and what they are told. Runs in the event's transaction. */
@Component
class InspectionAlerts {

    private final NotificationService notifications;
    private final TenantAccessService access;

    InspectionAlerts(NotificationService notifications, TenantAccessService access) {
        this.notifications = notifications;
        this.access = access;
    }

    void submitted(Inspection inspection, boolean criticalFailure) {
        String number = inspection.getInspectionNumber();
        if (criticalFailure) {
            Set<UUID> recipients = new HashSet<>(access.holdersOf(Permissions.INSPECTION_APPROVE));
            recipients.addAll(access.holdersOf(Permissions.DISPOSITION_RECORD));
            send(recipients, NotificationType.INSPECTION_CRITICAL_FAILURE, inspection,
                    "Critical failure on " + number,
                    "A critical parameter failed on inspection " + number + ". The material is on hold.");
        } else {
            send(access.holdersOf(Permissions.INSPECTION_APPROVE), NotificationType.INSPECTION_AWAITING_APPROVAL,
                    inspection, number + " is waiting for approval",
                    "Inspection " + number + " has been submitted and needs a review.");
        }
    }

    void approved(Inspection inspection) {
        String number = inspection.getInspectionNumber();
        send(access.holdersOf(Permissions.DISPOSITION_RECORD), NotificationType.INSPECTION_AWAITING_DISPOSITION,
                inspection, number + " needs a disposition",
                "Inspection " + number + " is approved with the result " + inspection.getFinalResult() + ".");
    }

    void sentBack(Inspection inspection, UUID inspector, String note) {
        String number = inspection.getInspectionNumber();
        send(Set.of(inspector), NotificationType.INSPECTION_SENT_BACK, inspection, number + " was sent back",
                "Inspection " + number + " needs correcting: " + note);
    }

    void disposed(Inspection inspection) {
        String number = inspection.getInspectionNumber();
        Set<UUID> recipients = new HashSet<>();
        recipients.add(inspection.getSubmittedBy());
        recipients.add(inspection.getApprovedBy());
        send(recipients, NotificationType.INSPECTION_DISPOSITION_RECORDED, inspection,
                "Disposition recorded for " + number,
                "Inspection " + number + " was closed with the disposition " + inspection.getDisposition() + ".");
    }

    private void send(Set<UUID> recipients, NotificationType type, Inspection inspection, String title, String body) {
        notifications.notify(recipients, type, title, body, "/inspections/" + inspection.getId());
    }
}
