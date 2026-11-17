package com.qualityos.identity.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.common.util.UuidV7;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Support access to an organisation's records, only after one of its administrators grants it, for a time they
 * choose, and withdrawn whenever they like. Read-only unless they also let support make changes, for at most a day;
 * approvals, sign-offs, access and billing stay with them either way. Each session, each record read and each change
 * is logged for them to see, and their administrators are told when a session that can make changes starts.
 */
@Service
public class SupportAccessService {

    /** The lengths an administrator can choose from. */
    public static final Set<Integer> DURATION_HOURS = Set.of(24, 72, 168);

    /** One session lasts at most this long, or until the grant ends if that comes first. */
    static final Duration SESSION = Duration.ofMinutes(30);

    /** The longest an administrator can let support make changes for. */
    public static final int CHANGES_MAX_HOURS = 24;

    public record Grant(UUID id, Instant grantedAt, Instant expiresAt, String grantedByName, String note,
            Instant revokedAt, String revokedByName, boolean open, boolean allowChanges) {
    }

    /** A grant in force, as the Control Center sees it. */
    public record OpenGrant(Instant expiresAt, boolean allowChanges) {
    }

    public record Event(String operatorName, String kind, String detail, Instant occurredAt) {
    }

    /** @param current the grant in force, or null */
    public record Overview(Grant current, List<Grant> grants, List<Event> events) {
    }

    public record Session(String accessToken, long expiresIn, Instant expiresAt, UUID tenantId, boolean allowChanges) {
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final AccessTokenIssuer tokens;
    private final TenantAccessService access;
    private final TransactionTemplate tx;
    private final com.qualityos.notification.service.NotificationService notifications;

    SupportAccessService(JdbcTemplate jdbc, AuditService audit, AccessTokenIssuer tokens, TenantAccessService access,
            PlatformTransactionManager transactionManager,
            com.qualityos.notification.service.NotificationService notifications) {
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.audit = audit;
        this.tokens = tokens;
        this.access = access;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public Overview overview() {
        Map<UUID, String> names = names();
        List<Grant> grants = jdbc.query("""
                SELECT id, granted_at, expires_at, granted_by, note, revoked_at, revoked_by, allow_changes,
                       (revoked_at IS NULL AND expires_at > now()) AS open
                FROM support_access_grants ORDER BY granted_at DESC LIMIT 20
                """, (rows, row) -> grant(rows, names));
        List<Event> events = jdbc.query("""
                SELECT operator_name, kind, detail, occurred_at FROM support_access_events
                ORDER BY occurred_at DESC LIMIT 200
                """, (rows, row) -> new Event(rows.getString("operator_name"), rows.getString("kind"),
                rows.getString("detail"), rows.getTimestamp("occurred_at").toInstant()));
        return new Overview(grants.stream().filter(Grant::open).findFirst().orElse(null), grants, events);
    }

    /** Replaces any grant still in force, so there is only ever one, and its end is the one just chosen. */
    @Transactional
    public Overview grant(int hours, String note, boolean allowChanges) {
        if (!DURATION_HOURS.contains(hours)) {
            throw new BusinessRuleException("Choose 24 hours, 3 days or 7 days.");
        }
        if (allowChanges && hours > CHANGES_MAX_HOURS) {
            throw new BusinessRuleException("Support can be let make changes for at most 24 hours.");
        }
        UUID me = TenantContext.getCurrentUserId();
        closeOpen(me);
        UUID id = UuidV7.generate();
        String trimmed = note == null || note.isBlank() ? null : note.strip();
        // Measured from the database's own clock, as the seven-day limit is.
        Instant expires = jdbc.queryForObject("""
                INSERT INTO support_access_grants (id, tenant_id, granted_by, expires_at, note, allow_changes)
                VALUES (?, ?, ?, now() + make_interval(hours => ?), ?, ?) RETURNING expires_at
                """, java.sql.Timestamp.class, id, TenantContext.getCurrentTenantId(), me, hours, trimmed, allowChanges)
                .toInstant();
        Map<String, Object> state = new HashMap<>(Map.of("hours", hours, "expiresAt", expires.toString(),
                "allowChanges", allowChanges));
        if (trimmed != null) {
            state.put("note", trimmed);
        }
        audit.record("SUPPORT_ACCESS_GRANTED", "Organization", TenantContext.getCurrentTenantId(), null, state);
        return overview();
    }

    /** Ends support access at once, including a session already open. */
    @Transactional
    public Overview revoke() {
        if (closeOpen(TenantContext.getCurrentUserId()) == 0) {
            throw new BusinessRuleException("Support has no access to withdraw.");
        }
        audit.record("SUPPORT_ACCESS_REVOKED", "Organization", TenantContext.getCurrentTenantId());
        return overview();
    }

    /** For the Control Center: the organisation's grant in force, if it has one. */
    public Optional<OpenGrant> openGrant(UUID tenantId) {
        return TenantContext.callAs(tenantId, TenantContext.getCurrentUserId(), () -> tx.execute(status -> jdbc.query("""
                SELECT expires_at, allow_changes FROM support_access_grants
                WHERE revoked_at IS NULL AND expires_at > now() ORDER BY expires_at DESC LIMIT 1
                """, (rows, row) -> new OpenGrant(rows.getTimestamp("expires_at").toInstant(),
                rows.getBoolean("allow_changes"))).stream().findFirst()));
    }

    /**
     * Opens a session for the signed-in operator, who must hold a role that handles customers: read-only, or able to
     * make changes if the grant allows them. Logged in the organisation's support log, with the reason given, and in
     * its audit trail. A session that can make changes is also announced to the organisation's administrators.
     */
    public Session startSession(UUID tenantId, String reason) {
        UUID operator = TenantContext.getCurrentUserId();
        record Opened(UUID grant, Instant expires, boolean changes) {
        }
        Opened opened = TenantContext.callAs(tenantId, operator, () -> tx.execute(status -> {
            List<Opened> found = jdbc.query("""
                    SELECT id, expires_at, allow_changes FROM support_access_grants
                    WHERE revoked_at IS NULL AND expires_at > now() ORDER BY expires_at DESC LIMIT 1
                    """, (rows, row) -> new Opened(rows.getObject("id", UUID.class),
                    rows.getTimestamp("expires_at").toInstant(), rows.getBoolean("allow_changes")));
            if (found.isEmpty()) {
                throw new BusinessRuleException(
                        "This organisation has not granted support access, or its access has ended.");
            }
            return found.getFirst();
        }));
        // Checked as each request of the session will be, so a session is never opened that could not be used.
        if (access.resolveSupportPermissions(tenantId, operator, opened.grant()).isEmpty()) {
            throw new BusinessRuleException("Only an operator who handles customers can open a support session.");
        }
        TenantContext.runAs(tenantId, operator, () -> tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO support_access_events (id, tenant_id, grant_id, operator_id, operator_name, kind, detail)
                    SELECT ?, ?, ?, u.id, u.full_name, 'STARTED', ? FROM users u WHERE u.id = ?
                    """, UuidV7.generate(), tenantId, opened.grant(), reason.strip(), operator);
            audit.record("SUPPORT_SESSION_STARTED", "Organization", tenantId, null,
                    Map.of("reason", reason.strip(), "grant", opened.grant().toString(),
                            "allowChanges", opened.changes()));
            if (opened.changes()) {
                String operatorName = jdbc.queryForObject("SELECT full_name FROM users WHERE id = ?", String.class,
                        operator);
                notifications.notifyIncludingActor(access.holdersOf(com.qualityos.common.security.Permissions.TENANT_MANAGE),
                        com.qualityos.notification.service.NotificationType.SUPPORT_CHANGES_STARTED,
                        "Support is working in your organisation",
                        operatorName + " from ProductionOS support started a session that can make changes. Reason: "
                                + reason.strip() + ". Each change is in the support log, and you can withdraw access "
                                + "at any time.",
                        "/settings", null);
            }
        }));
        Instant ends = Instant.now().plus(SESSION);
        Instant expires = opened.expires().isBefore(ends) ? opened.expires() : ends;
        return new Session(tokens.issueSupport(operator, tenantId, opened.grant(), expires),
                Duration.between(Instant.now(), expires).toSeconds(), expires, tenantId, opened.changes());
    }

    private int closeOpen(UUID by) {
        return jdbc.update("""
                UPDATE support_access_grants SET revoked_at = now(), revoked_by = ?
                WHERE revoked_at IS NULL AND expires_at > now()
                """, by);
    }

    private Map<UUID, String> names() {
        return access.names();
    }

    private static Grant grant(ResultSet rows, Map<UUID, String> names) throws SQLException {
        java.sql.Timestamp revoked = rows.getTimestamp("revoked_at");
        UUID revokedBy = rows.getObject("revoked_by", UUID.class);
        return new Grant(rows.getObject("id", UUID.class), rows.getTimestamp("granted_at").toInstant(),
                rows.getTimestamp("expires_at").toInstant(), names.get(rows.getObject("granted_by", UUID.class)),
                rows.getString("note"), revoked == null ? null : revoked.toInstant(),
                revokedBy == null ? null : names.get(revokedBy), rows.getBoolean("open"),
                rows.getBoolean("allow_changes"));
    }
}
