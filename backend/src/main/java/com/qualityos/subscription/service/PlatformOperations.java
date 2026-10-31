package com.qualityos.subscription.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ErrorCode;
import com.qualityos.common.exception.QualityOsException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.job.JobMonitor;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.identity.service.AuthService;
import com.qualityos.notification.service.NotificationService;
import com.qualityos.subscription.dto.PlatformOperationsDtos.AddOperatorRequest;
import com.qualityos.subscription.dto.PlatformOperationsDtos.AuditEntry;
import com.qualityos.subscription.dto.PlatformOperationsDtos.AuditFilter;
import com.qualityos.subscription.dto.PlatformOperationsDtos.AuditList;
import com.qualityos.subscription.dto.PlatformOperationsDtos.ChangeOperatorRequest;
import com.qualityos.subscription.dto.PlatformOperationsDtos.EmailEntry;
import com.qualityos.subscription.dto.PlatformOperationsDtos.EmailList;
import com.qualityos.subscription.dto.PlatformOperationsDtos.NotificationEntry;
import com.qualityos.subscription.dto.PlatformOperationsDtos.NotificationList;
import com.qualityos.subscription.dto.PlatformOperationsDtos.Operator;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Who runs the platform, and what the platform itself has been doing. */
@Service
public class PlatformOperations {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final int MAX_EXPORT = 5000;
    private static final Set<String> ROLES = Set.of("SUPER_ADMIN", "BILLING_ADMIN", "SUPPORT_ADMIN", "READ_ONLY_AUDITOR");
    private static final Set<String> NOTICES = Set.of("SUBSCRIPTION_EXPIRY", "PAYMENT_RECEIVED", "INVOICE_OVERDUE",
            "PLAN_CHANGED");
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i).*(password|secret|token|hash|otp|credential|gstin|bank|utr).*");
    private static final String HIDDEN = "[hidden]";
    private static final String AUDIT = "SELECT * FROM platform_audit_page(?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final AuthService auth;
    private final PlatformStepUp stepUp;
    private final NotificationService notifications;
    private final JobMonitor jobs;
    private final JsonMapper json;
    private final TransactionTemplate tx;

    PlatformOperations(
            JdbcTemplate jdbc,
            AuditService audit,
            AuthService auth,
            PlatformStepUp stepUp,
            NotificationService notifications,
            JobMonitor jobs,
            JsonMapper json,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.auth = auth;
        this.stepUp = stepUp;
        this.notifications = notifications;
        this.jobs = jobs;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Must run with no open transaction, because a wrong password has to be counted even though this fails. */
    public void reauthenticate(String password) {
        auth.confirmPassword(password);
        tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT platform_mark_reauthenticated(?)::text", String.class,
                    PlatformStepUp.WINDOW_MINUTES);
            audit.record("OPERATOR_REAUTHENTICATED", "PlatformOperator", TenantContext.getCurrentUserId());
        });
    }

    public List<Operator> operators() {
        return tx.execute(status -> jdbc.query("SELECT * FROM platform_operators()", (rows, row) -> new Operator(
                rows.getObject("user_id", UUID.class), rows.getString("email"), rows.getString("full_name"),
                rows.getString("role"), instant(rows, "added_at"), instant(rows, "deactivated_at"),
                rows.getString("added_by"), instant(rows, "last_activity"))));
    }

    public void add(AddOperatorRequest request) {
        requireRole(request.role());
        stepUp.require();
        tx.executeWithoutResult(status -> {
            String outcome = jdbc.queryForObject(
                    "SELECT platform_operator_add(?, ?)", String.class, request.email().trim(), request.role());
            if ("NO_ACCOUNT".equals(outcome)) {
                throw new BusinessRuleException("There is no confirmed account with this email. They must register first.");
            }
            if ("ALREADY_OPERATOR".equals(outcome)) {
                throw new ConflictException("This account is already an operator. Change it from the list instead.");
            }
            audit.record("OPERATOR_ADDED", "PlatformOperator", outcome, null,
                    Map.of("role", request.role(), "reason", request.reason()));
        });
    }

    public void change(UUID user, ChangeOperatorRequest request) {
        requireRole(request.role());
        stepUp.require();
        tx.executeWithoutResult(status -> {
            String outcome = jdbc.queryForObject("SELECT platform_operator_change(?, ?, ?)", String.class, user,
                    request.role(), request.active());
            if ("NOT_FOUND".equals(outcome)) {
                throw new ResourceNotFoundException("Operator", user.toString());
            }
            if ("LAST_SUPER_ADMIN".equals(outcome)) {
                throw new BusinessRuleException("This is the last Super Admin. Make someone else a Super Admin first.");
            }
            audit.record(request.active() ? "OPERATOR_UPDATED" : "OPERATOR_DEACTIVATED", "PlatformOperator", user, null,
                    Map.of("role", request.role(), "active", request.active(), "reason", request.reason()));
        });
    }

    private static void requireRole(String role) {
        if (!ROLES.contains(role)) {
            throw new BusinessRuleException("Unknown role.");
        }
    }

    public AuditList auditLog(AuditFilter filter, int page, int size) {
        List<Long> total = new ArrayList<>();
        List<AuditEntry> rows = auditRows(filter, size, page * size, total);
        return new AuditList(total.isEmpty() ? 0 : total.getFirst().intValue(), rows);
    }

    /** Leaves a trace in the log it reads. */
    public List<AuditEntry> exportAuditLog(AuditFilter filter) {
        List<AuditEntry> rows = auditRows(filter, MAX_EXPORT, 0, new ArrayList<>());
        tx.executeWithoutResult(status -> audit.record(
                "AUDIT_LOG_EXPORTED", "PlatformOperator", null, null, Map.of("rows", rows.size())));
        return rows;
    }

    private List<AuditEntry> auditRows(AuditFilter filter, int limit, int offset, List<Long> total) {
        return tx.execute(status -> jdbc.query(AUDIT, (rows, row) -> {
            total.clear();
            total.add(rows.getLong("total"));
            return new AuditEntry(rows.getObject("id", UUID.class), instant(rows, "occurred_at"), rows.getString("actor"),
                    rows.getString("action"), rows.getString("entity_type"), rows.getString("entity_id"),
                    rows.getString("organisation"), redacted(rows.getString("previous_state")),
                    redacted(rows.getString("new_state")), rows.getString("reason"), rows.getString("correlation_id"),
                    rows.getString("ip_address"));
        }, text(filter.q()), text(filter.action()), text(filter.entityType()), opening(filter.from()),
                filter.to() == null ? null : opening(filter.to().plusDays(1)), limit, offset));
    }

    private static OffsetDateTime opening(LocalDate day) {
        return day == null ? null : day.atStartOfDay(ZONE).toOffsetDateTime();
    }

    /** Audit rows are never changed, so sensitive values are withheld on the way out. */
    private Map<String, Object> redacted(String state) {
        return state == null ? null : redact(json.readValue(state, new TypeReference<Map<String, Object>>() { }));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> redact(Map<String, Object> state) {
        Map<String, Object> safe = new LinkedHashMap<>();
        state.forEach((key, value) -> {
            if (SENSITIVE.matcher(key).matches()) {
                safe.put(key, HIDDEN);
            } else if (value instanceof Map<?, ?> nested) {
                safe.put(key, redact((Map<String, Object>) nested));
            } else {
                safe.put(key, value);
            }
        });
        return safe;
    }

    public EmailList emails(String q, String state, boolean retryable, int page, int size) {
        List<Long> total = new ArrayList<>();
        List<EmailEntry> rows = tx.execute(status -> jdbc.query(
                "SELECT * FROM platform_email_page(?, ?, ?, ?, ?)", (result, row) -> {
                    total.clear();
                    total.add(result.getLong("total"));
                    return new EmailEntry(result.getObject("id", UUID.class), result.getObject("tenant_id", UUID.class),
                            result.getString("organisation"), result.getObject("notification_id", UUID.class),
                            result.getString("recipient"), result.getString("template"), result.getString("status"),
                            result.getLong("attempts"), instant(result, "last_attempt"), result.getString("error_type"),
                            result.getBoolean("retryable"));
                }, text(q), text(state), retryable, size, page * size));
        return new EmailList(total.isEmpty() ? 0 : total.getFirst().intValue(), rows);
    }

    public void retryEmail(UUID tenant, UUID notification, String reason) {
        inside(tenant, () -> {
            notifications.retryEmail(notification);
            audit.record("EMAIL_RETRY_REQUESTED", "Notification", notification, null, Map.of("reason", reason));
            return null;
        });
    }

    public NotificationList notices(String type, int page, int size) {
        if (type != null && !NOTICES.contains(type)) {
            throw new BusinessRuleException("Unknown notification type.");
        }
        List<Long> total = new ArrayList<>();
        List<NotificationEntry> rows = tx.execute(status -> jdbc.query(
                "SELECT * FROM platform_notification_page(?, ?, ?)", (result, row) -> {
                    total.clear();
                    total.add(result.getLong("total"));
                    return new NotificationEntry(result.getObject("id", UUID.class), instant(result, "sent_at"),
                            result.getString("type"), result.getString("title"), result.getString("organisation"),
                            result.getObject("tenant_id", UUID.class), result.getString("recipient"),
                            result.getString("email_status"));
                }, type, size, page * size));
        return new NotificationList(total.isEmpty() ? 0 : total.getFirst().intValue(), rows);
    }

    public List<JobMonitor.JobOverview> jobs() {
        return jobs.overview(Instant.now());
    }

    public List<JobMonitor.JobRun> jobRuns(String job) {
        return jobs.runs(job).orElseThrow(() -> new ResourceNotFoundException("Job", job));
    }

    private <T> T inside(UUID tenant, Supplier<T> work) {
        return TenantContext.callAs(tenant, TenantContext.getCurrentUserId(), () -> tx.execute(status -> work.get()));
    }

    private static String text(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static Instant instant(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
