package com.qualityos.subscription.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.mail.EmailContent;
import com.qualityos.common.mail.EmailTemplate;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.identity.service.TenantAccessService;
import com.qualityos.notification.service.NotificationService;
import com.qualityos.notification.service.NotificationType;
import com.qualityos.subscription.dto.PlatformConsoleDtos.AddNoteRequest;
import com.qualityos.subscription.dto.PlatformConsoleDtos.Announcement;
import com.qualityos.subscription.dto.PlatformConsoleDtos.CustomerAnnouncement;
import com.qualityos.subscription.dto.PlatformConsoleDtos.CustomerNotices;
import com.qualityos.subscription.dto.PlatformConsoleDtos.OpenTicketRequest;
import com.qualityos.subscription.dto.PlatformConsoleDtos.PlatformSettings;
import com.qualityos.subscription.dto.PlatformConsoleDtos.SaveAnnouncementRequest;
import com.qualityos.subscription.dto.PlatformConsoleDtos.SaveSettingsRequest;
import com.qualityos.subscription.dto.PlatformConsoleDtos.SupportError;
import com.qualityos.subscription.dto.PlatformConsoleDtos.Ticket;
import com.qualityos.subscription.dto.PlatformConsoleDtos.TicketList;
import com.qualityos.subscription.dto.PlatformConsoleDtos.TicketNote;
import com.qualityos.subscription.dto.PlatformConsoleDtos.TicketStatusRequest;
import java.sql.Date;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Settings, announcements and support tickets. Audit rows here stay in the operator's own trail, never the customer's,
 * and hold only ids and states, because an organisation can read its own trail and notes must never reach it.
 */
@Service
public class PlatformConsole {

    private static final String SETTINGS = """
            SELECT default_trial_days, default_grace_days, maintenance_mode, registration_open, support_email, version,
                   updated_at
            FROM platform_settings
            """;
    private static final String SAVE_ANNOUNCEMENT = "SELECT platform_announcement_save(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String MOVED_ON = "Someone else changed this first. Reload and try again.";

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final PlatformStepUp stepUp;
    private final NotificationService notifications;
    private final TenantAccessService access;
    private final TransactionTemplate tx;

    PlatformConsole(
            JdbcTemplate jdbc,
            AuditService audit,
            PlatformStepUp stepUp,
            NotificationService notifications,
            TenantAccessService access,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.stepUp = stepUp;
        this.notifications = notifications;
        this.access = access;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public CustomerNotices customerNotices() {
        return tx.execute(status -> {
            PlatformSettings settings = readSettings();
            return new CustomerNotices(settings.maintenanceMode(), settings.supportEmail(), jdbc.query(
                    "SELECT * FROM current_announcements()", (rows, row) -> new CustomerAnnouncement(
                            rows.getObject("id", UUID.class), rows.getString("title"), rows.getString("message"),
                            rows.getString("severity"))));
        });
    }

    public PlatformSettings settings() {
        return tx.execute(status -> readSettings());
    }

    private PlatformSettings readSettings() {
        return jdbc.queryForObject(SETTINGS, (rows, row) -> new PlatformSettings(
                rows.getInt("default_trial_days"), rows.getInt("default_grace_days"),
                rows.getBoolean("maintenance_mode"), rows.getBoolean("registration_open"),
                rows.getString("support_email"), rows.getLong("version"), rows.getTimestamp("updated_at").toInstant()));
    }

    public PlatformSettings saveSettings(SaveSettingsRequest request) {
        stepUp.require();
        return tx.execute(status -> {
            PlatformSettings before = readSettings();
            Boolean saved = jdbc.queryForObject("SELECT platform_settings_save(?, ?, ?, ?, ?, ?)", Boolean.class,
                    request.defaultTrialDays(), request.defaultGraceDays(), request.maintenanceMode(),
                    request.registrationOpen(), text(request.supportEmail()), request.version());
            if (!Boolean.TRUE.equals(saved)) {
                throw new ConflictException(MOVED_ON);
            }
            PlatformSettings after = readSettings();
            audit.record("SETTINGS_CHANGED", "PlatformSettings", null, state(before, null), state(after, request.reason()));
            return after;
        });
    }

    private static Map<String, Object> state(PlatformSettings settings, String reason) {
        Map<String, Object> state = new HashMap<>();
        state.put("defaultTrialDays", settings.defaultTrialDays());
        state.put("defaultGraceDays", settings.defaultGraceDays());
        state.put("maintenanceMode", settings.maintenanceMode());
        state.put("registrationOpen", settings.registrationOpen());
        state.put("supportEmail", settings.supportEmail());
        if (reason != null) {
            state.put("reason", reason);
        }
        return state;
    }

    public List<Announcement> announcements() {
        return tx.execute(status -> jdbc.query("SELECT * FROM platform_announcement_list()", (rows, row) ->
                new Announcement(rows.getObject("id", UUID.class), rows.getString("title"), rows.getString("message"),
                        rows.getString("severity"), rows.getString("audience"), rows.getDate("starts_on").toLocalDate(),
                        rows.getDate("ends_on").toLocalDate(), rows.getBoolean("active"), rows.getBoolean("email"),
                        rows.getLong("version"))));
    }

    /** @param id empty to create one */
    public void saveAnnouncement(UUID id, SaveAnnouncementRequest request) {
        if (request.endsOn().isBefore(request.startsOn())) {
            throw new BusinessRuleException("The end date cannot be before the start date.");
        }
        tx.executeWithoutResult(status -> {
            UUID saved = jdbc.queryForObject(SAVE_ANNOUNCEMENT, UUID.class, id, request.title().trim(),
                    request.message().trim(), request.severity(), request.audience(), Date.valueOf(request.startsOn()),
                    Date.valueOf(request.endsOn()), request.active(), request.emailed(), request.version());
            if (saved == null) {
                throw new ConflictException(MOVED_ON);
            }
            audit.record(id == null ? "ANNOUNCEMENT_CREATED" : "ANNOUNCEMENT_UPDATED", "Announcement", saved, null,
                    Map.of("title", request.title(), "severity", request.severity(), "audience", request.audience(),
                            "active", request.active(), "email", request.emailed(), "reason", request.reason()));
        });
    }

    public TicketList tickets(UUID tenant, String state, int page, int size) {
        List<Long> total = new ArrayList<>();
        List<Ticket> rows = tx.execute(status -> jdbc.query(
                "SELECT * FROM platform_support_tickets(?, ?, ?, ?)", (result, row) -> {
                    total.clear();
                    total.add(result.getLong("total"));
                    return new Ticket(result.getObject("id", UUID.class), result.getObject("tenant_id", UUID.class),
                            result.getString("organisation"), result.getString("subject"), result.getString("status"),
                            result.getString("opened_by"), result.getBoolean("raised_by_customer"),
                            result.getTimestamp("created_at").toInstant(),
                            result.getTimestamp("updated_at").toInstant(), result.getLong("version"));
                }, tenant, text(state), size, page * size));
        return new TicketList(total.isEmpty() ? 0 : total.getFirst().intValue(), rows);
    }

    public void open(OpenTicketRequest request) {
        tx.executeWithoutResult(status -> {
            UUID ticket = jdbc.queryForObject("SELECT platform_support_open(?, ?, ?)", UUID.class,
                    request.tenantId(), request.subject().trim(), request.note().trim());
            if (ticket == null) {
                throw new ResourceNotFoundException("Organisation", request.tenantId().toString());
            }
            audit.record("TICKET_OPENED", "SupportTicket", ticket, null, Map.of("tenantId", request.tenantId().toString()));
        });
    }

    public void changeStatus(UUID ticket, TicketStatusRequest request) {
        tx.executeWithoutResult(status -> {
            refuse(jdbc.queryForObject("SELECT platform_support_status(?, ?, ?)", String.class, ticket, request.status(),
                    request.version()), ticket);
            audit.record("TICKET_STATUS_CHANGED", "SupportTicket", ticket, null, Map.of("status", request.status()));
        });
        tellCustomer(ticket, "Its status is now " + request.status().toLowerCase().replace('_', ' ') + ".");
    }

    public void addNote(UUID ticket, AddNoteRequest request) {
        tx.executeWithoutResult(status -> {
            refuse(jdbc.queryForObject("SELECT platform_support_note(?, ?, ?)", String.class, ticket,
                    request.body().trim(), request.shared()), ticket);
            audit.record("TICKET_NOTE_ADDED", "SupportTicket", ticket, null, Map.of("toCustomer", request.shared()));
        });
        if (request.shared()) {
            tellCustomer(ticket, "Support replied.");
        }
    }

    /**
     * Tells whoever raised a customer ticket that something changed, in the app and by email. The message names no
     * operator and repeats nothing from the notes, so it says only that there is something to read.
     */
    private void tellCustomer(UUID ticket, String update) {
        record Contact(UUID tenantId, UUID openedBy, String subject) {
        }
        List<Contact> found = tx.execute(status -> jdbc.query(
                "SELECT * FROM platform_support_ticket_contact(?) WHERE raised_by_customer", (rows, row) -> new Contact(
                        rows.getObject("tenant_id", UUID.class), rows.getObject("opened_by", UUID.class),
                        rows.getString("subject")), ticket));
        if (found == null || found.isEmpty()) {
            return;
        }
        Contact contact = found.getFirst();
        TenantContext.runAs(contact.tenantId(), null, () -> tx.executeWithoutResult(status -> {
            if (access.activeMemberIds().contains(contact.openedBy())) {
                notifications.notifyIncludingActor(Set.of(contact.openedBy()), NotificationType.SUPPORT_TICKET_UPDATED,
                        "Your support request: " + contact.subject(), update, "/support/" + ticket,
                        new EmailContent(EmailTemplate.SUPPORT_UPDATE, Map.of("subject", contact.subject(),
                                "update", update)));
            }
        }));
    }

    private static void refuse(String outcome, UUID ticket) {
        switch (outcome) {
            case "NOT_FOUND" -> throw new ResourceNotFoundException("Ticket", ticket.toString());
            case "CLOSED" -> throw new BusinessRuleException("This ticket is closed. Open a new one instead.");
            case "STALE" -> throw new ConflictException(MOVED_ON);
            case "INTERNAL" -> throw new BusinessRuleException(
                    "Support opened this ticket, so the customer cannot see it. Add an internal note instead.");
            default -> { }
        }
    }

    public List<TicketNote> notes(UUID ticket) {
        return tx.execute(status -> jdbc.query("SELECT * FROM platform_support_notes(?)", (rows, row) -> new TicketNote(
                rows.getObject("id", UUID.class), rows.getString("author"), rows.getString("body"),
                rows.getBoolean("shared"), rows.getTimestamp("created_at").toInstant()), ticket));
    }

    public List<SupportError> errors(UUID tenant) {
        return tx.execute(status -> jdbc.query("SELECT * FROM platform_support_errors(?)", (rows, row) -> new SupportError(
                rows.getTimestamp("occurred_at").toInstant(), rows.getString("template"), rows.getString("error_type")),
                tenant));
    }

    private static String text(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
