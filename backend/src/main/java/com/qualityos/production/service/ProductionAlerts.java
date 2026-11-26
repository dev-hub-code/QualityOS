package com.qualityos.production.service;

import com.qualityos.common.mail.EmailContent;
import com.qualityos.common.mail.EmailTemplate;
import com.qualityos.common.security.Permissions;
import com.qualityos.identity.service.FacilityScope;
import com.qualityos.identity.service.TenantAccessService;
import com.qualityos.notification.service.NotificationService;
import com.qualityos.notification.service.NotificationType;
import com.qualityos.production.entity.Machine;
import com.qualityos.production.entity.ProductionPlan;
import com.qualityos.production.entity.ReworkOrder;
import com.qualityos.production.entity.RevisionedDefinition;
import com.qualityos.production.entity.WorkOrder;
import com.qualityos.production.entity.WorkOrderOperation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Says who hears about each production event. Runs in the event's transaction, so nobody is told about something
 * that did not happen. Work at a facility reaches only those who can see that facility; BOMs, recipes and routings
 * belong to no facility, so they reach everyone allowed to approve them.
 */
@Component
class ProductionAlerts {

    private static final String WORK_ORDERS = "/manufacturing/work-orders/";

    private final NotificationService notifications;
    private final TenantAccessService access;
    private final FacilityScope scope;

    ProductionAlerts(NotificationService notifications, TenantAccessService access, FacilityScope scope) {
        this.notifications = notifications;
        this.access = access;
        this.scope = scope;
    }

    void workOrderSubmitted(WorkOrder order, String productCode) {
        String summary = productCode + " × " + plain(order.getQuantity()) + ", planned " + order.getPlannedStart()
                + " to " + order.getPlannedEnd();
        atFacility(order.getFacilityId(), access.holdersOf(Permissions.WORK_ORDER_APPROVE),
                NotificationType.WORK_ORDER_AWAITING_APPROVAL, order.getWorkOrderNumber() + " needs approval", summary,
                WORK_ORDERS + order.getId(), approval(order.getWorkOrderNumber(), summary));
    }

    void workOrderSentBack(WorkOrder order, String note) {
        atFacility(order.getFacilityId(), recipient(order.getSubmittedBy()), NotificationType.WORK_ORDER_SENT_BACK,
                order.getWorkOrderNumber() + " was sent back", note, WORK_ORDERS + order.getId(),
                sentBack(order.getWorkOrderNumber(), note));
    }

    /**
     * @param label how the revision is named, such as "BOM SH-1 revision 2"
     * @param approvePermission who may approve this kind of definition
     * @param link the revision's page
     */
    void definitionSubmitted(RevisionedDefinition revision, String label, String approvePermission, String link) {
        String summary = revision.getChangeNote() == null || revision.getChangeNote().isBlank()
                ? "no change note" : revision.getChangeNote();
        notifications.notify(access.holdersOf(approvePermission), NotificationType.DEFINITION_AWAITING_APPROVAL,
                label + " needs approval", summary, link, approval(label, summary));
    }

    void definitionSentBack(RevisionedDefinition revision, String label, String note, String link) {
        notifications.notify(recipient(revision.getSubmittedBy()), NotificationType.DEFINITION_SENT_BACK,
                label + " was sent back", note, link, sentBack(label, note));
    }

    void reworkRaised(ReworkOrder rework, WorkOrder order) {
        String summary = plain(rework.getQuantity()) + " from " + order.getWorkOrderNumber();
        atFacility(order.getFacilityId(), access.holdersOf(Permissions.REWORK_APPROVE),
                NotificationType.REWORK_AWAITING_APPROVAL, rework.getReworkNumber() + " needs approval", summary,
                "/manufacturing/rework-orders/" + rework.getId(), approval(rework.getReworkNumber(), summary));
    }

    void qualityHold(WorkOrder order, String reason) {
        String where = order.getWorkOrderNumber() + " (quality hold)";
        atFacility(order.getFacilityId(), supervisors(), NotificationType.PRODUCTION_QUALITY_HOLD,
                order.getWorkOrderNumber() + " is on quality hold", reason, WORK_ORDERS + order.getId(),
                stopped(where, reason));
    }

    void operationBlocked(WorkOrder order, WorkOrderOperation operation, String reason) {
        String where = order.getWorkOrderNumber() + " operation " + operation.getSequence();
        atFacility(order.getFacilityId(), supervisors(), NotificationType.OPERATION_BLOCKED, where + " is blocked",
                reason, "/manufacturing/shop-floor/" + operation.getId(), stopped(where, reason));
    }

    /** A reading outside its limits goes to the production supervisors and quality, who decide what to do with it. */
    void readingOutOfLimits(WorkOrder order, WorkOrderOperation operation, String parameter, String reading,
            String comment) {
        String where = order.getWorkOrderNumber() + " operation " + operation.getSequence();
        Set<UUID> people = new HashSet<>(supervisors());
        people.addAll(access.holdersOf(Permissions.NCR_CREATE));
        String body = parameter + " read " + reading + ". " + comment;
        atFacility(order.getFacilityId(), people, NotificationType.PROCESS_OUT_OF_LIMITS,
                parameter + " is outside its limits on " + where, body,
                "/manufacturing/shop-floor/" + operation.getId(), stopped(where, body));
    }

    void machineBrokeDown(Machine machine, UUID facilityId, String reason) {
        String where = machine.getCode() + " – " + machine.getName();
        String why = reason == null || reason.isBlank() ? "breakdown" : reason.trim();
        Set<UUID> people = new HashSet<>(supervisors());
        people.addAll(access.holdersOf(Permissions.DOWNTIME_MANAGE));
        atFacility(facilityId, people, NotificationType.MACHINE_BREAKDOWN, machine.getCode() + " has broken down", why,
                "/manufacturing/machines/" + machine.getId(), stopped(where, why));
    }

    void workOrderOverdue(WorkOrder order, String productCode) {
        String summary = productCode + " × " + plain(order.getQuantity()) + ", " + order.getStatus().name()
                .toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        atFacility(order.getFacilityId(), access.holdersOf(Permissions.WORK_ORDER_MANAGE),
                NotificationType.WORK_ORDER_OVERDUE, order.getWorkOrderNumber() + " is past its planned end",
                "Planned to end on " + order.getPlannedEnd() + ". " + summary, WORK_ORDERS + order.getId(),
                overdue(order.getWorkOrderNumber(), order.getPlannedEnd(), summary));
    }

    void planLineOverdue(ProductionPlan plan, String productCode, BigDecimal planned, BigDecimal made, LocalDate due) {
        String label = plan.getPlanNumber() + " " + productCode;
        String summary = plain(made) + " of " + plain(planned) + " made";
        atFacility(plan.getFacilityId(), access.holdersOf(Permissions.PRODUCTION_PLAN_MANAGE),
                NotificationType.PLAN_LINE_OVERDUE, label + " is past its due date",
                "Due on " + due + ". " + summary + ".", "/manufacturing/production-plans/" + plan.getId(),
                overdue(label, due, summary));
    }

    private Set<UUID> supervisors() {
        return access.holdersOf(Permissions.PRODUCTION_SUPERVISE);
    }

    private void atFacility(UUID facilityId, Set<UUID> to, NotificationType type, String title, String body,
            String link, EmailContent email) {
        notifications.notify(scope.allowedIn(facilityId, to), type, title, body, link, email);
    }

    private static Set<UUID> recipient(UUID person) {
        return person == null ? Set.of() : Set.of(person);
    }

    private static EmailContent approval(String recordNumber, String summary) {
        return new EmailContent(EmailTemplate.PRODUCTION_APPROVAL, Map.of("recordNumber", recordNumber, "summary", summary));
    }

    private static EmailContent sentBack(String recordNumber, String note) {
        return new EmailContent(EmailTemplate.PRODUCTION_SENT_BACK, Map.of("recordNumber", recordNumber, "note", note));
    }

    private static EmailContent stopped(String where, String reason) {
        return new EmailContent(EmailTemplate.PRODUCTION_STOPPED, Map.of("where", where, "reason", reason));
    }

    private static EmailContent overdue(String recordNumber, LocalDate due, String summary) {
        return new EmailContent(EmailTemplate.PRODUCTION_OVERDUE,
                Map.of("recordNumber", recordNumber, "dueDate", due.toString(), "summary", summary));
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
