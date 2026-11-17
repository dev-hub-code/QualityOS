package com.qualityos.identity.service;

import com.qualityos.common.util.UuidV7;
import com.qualityos.common.security.TenantAccessResolver;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.identity.dto.AuthDtos.OrganizationSummary;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import com.qualityos.common.security.Permissions;
import com.qualityos.subscription.service.PlanAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TenantAccessService implements TenantAccessResolver {

    private static final String PERMISSIONS = """
            SELECT p.code
            FROM tenant_memberships m
            JOIN users u ON u.id = m.user_id AND u.status = 'ACTIVE'
            JOIN tenants t ON t.id = m.tenant_id AND t.status = 'ACTIVE'
            LEFT JOIN user_roles ur ON ur.membership_id = m.id
            LEFT JOIN role_permissions rp ON rp.role_id = ur.role_id
            LEFT JOIN permissions p ON p.id = rp.permission_id
            WHERE m.tenant_id = ? AND m.user_id = ? AND m.status = 'ACTIVE'
            """;

    private static final String ORGANIZATIONS = """
            SELECT t.id, t.name
            FROM tenant_memberships m
            JOIN tenants t ON t.id = m.tenant_id AND t.status = 'ACTIVE'
            WHERE m.user_id = ? AND m.status = 'ACTIVE'
            ORDER BY m.created_at, t.id
            """;

    private static final String HOLDERS = """
            SELECT DISTINCT m.user_id
            FROM tenant_memberships m
            JOIN users u ON u.id = m.user_id AND u.status = 'ACTIVE'
            JOIN user_roles ur ON ur.membership_id = m.id
            JOIN role_permissions rp ON rp.role_id = ur.role_id
            JOIN permissions p ON p.id = rp.permission_id
            WHERE m.tenant_id = ? AND m.status = 'ACTIVE' AND p.code = ?
            """;

    private static final String ACTIVE_EMAIL = """
            SELECT u.email, u.full_name
            FROM tenant_memberships m
            JOIN users u ON u.id = m.user_id AND u.status = 'ACTIVE'
            WHERE m.tenant_id = ? AND m.user_id = ? AND m.status = 'ACTIVE'
            """;

    private static final String ACTIVE_MEMBERS = """
            SELECT u.id, u.full_name
            FROM tenant_memberships m
            JOIN users u ON u.id = m.user_id AND u.status = 'ACTIVE'
            WHERE m.tenant_id = ? AND m.status = 'ACTIVE'
            ORDER BY u.full_name
            """;

    private static final String PLATFORM_ADMIN = "SELECT role FROM platform_admins WHERE user_id = ? AND deactivated_at IS NULL";

    private static final String OPEN_ACCOUNT = "SELECT 1 FROM users WHERE id = ? AND status <> 'DISABLED'";


    private final JdbcTemplate jdbc;
    private final PlanAccess plan;
    private final TransactionTemplate readOnly;
    private final TransactionTemplate writable;

    TenantAccessService(JdbcTemplate jdbc, PlanAccess plan, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.plan = plan;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.writable = new TransactionTemplate(transactionManager);
    }

    /** Reading, nothing more. Production costs stay private: they are commercial, not quality, records. */
    private static final String SUPPORT_PERMISSIONS = """
            SELECT code FROM permissions WHERE code LIKE '%\\_VIEW' ESCAPE '\\' AND code <> 'PRODUCTION_COST_VIEW'
            """;

    /** Whether the grant allows changes, or no row once it has ended. */
    private static final String OPEN_GRANT = """
            SELECT g.allow_changes FROM support_access_grants g JOIN tenants t ON t.id = g.tenant_id
            WHERE g.id = ? AND g.tenant_id = ? AND g.revoked_at IS NULL AND g.expires_at > now()
              AND t.status = 'ACTIVE'
            """;

    private static final String ALL_PERMISSIONS = "SELECT code FROM permissions";

    /** Operators who made changes here in support sessions. */
    private static final String SUPPORT_OPERATORS = """
            SELECT DISTINCT ON (operator_id) operator_id, operator_name FROM support_access_events
            WHERE tenant_id = ? AND kind = 'CHANGED' ORDER BY operator_id, occurred_at DESC
            """;

    @Override
    public Optional<Set<String>> resolveSupportPermissions(UUID tenantId, UUID operatorId, UUID grantId) {
        return TenantContext.callAs(tenantId, operatorId, () -> readOnly.execute(status -> {
            List<Boolean> grant = jdbc.queryForList(OPEN_GRANT, Boolean.class, grantId, tenantId);
            boolean open = !grant.isEmpty();
            boolean changes = open && Boolean.TRUE.equals(grant.getFirst());
            boolean handlesCustomers = jdbc.queryForList(PLATFORM_ADMIN, String.class, operatorId).stream()
                    .anyMatch(role -> Permissions.PLATFORM_ROLES.getOrDefault(role, Set.of())
                            .contains(Permissions.PLATFORM_CUSTOMER_MANAGE));
            if (!open || !handlesCustomers || jdbc.queryForList(OPEN_ACCOUNT, Integer.class, operatorId).isEmpty()) {
                return Optional.<Set<String>>empty();
            }
            if (!changes) {
                return Optional.of(plan.narrow(tenantId, Set.copyOf(jdbc.queryForList(SUPPORT_PERMISSIONS, String.class))));
            }
            Set<String> granted = new HashSet<>(plan.narrow(tenantId, jdbc.queryForList(ALL_PERMISSIONS, String.class)
                    .stream().filter(Permissions::supportMayHold).collect(Collectors.toSet())));
            granted.add(SUPPORT_CHANGES);
            return Optional.of(Set.copyOf(granted));
        }));
    }

    @Override
    public void recordSupportView(UUID tenantId, UUID operatorId, UUID grantId, String detail) {
        TenantContext.runAs(tenantId, operatorId, () -> writable.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO support_access_events (id, tenant_id, grant_id, operator_id, operator_name, kind, detail)
                SELECT ?, ?, ?, u.id, u.full_name, 'VIEWED', ? FROM users u WHERE u.id = ?
                """, UuidV7.generate(), tenantId, grantId, left(detail), operatorId)));
    }

    @Override
    public void recordSupportChange(UUID tenantId, UUID operatorId, UUID grantId, String detail) {
        TenantContext.runAs(tenantId, operatorId, () -> writable.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO support_access_events (id, tenant_id, grant_id, operator_id, operator_name, kind, detail)
                SELECT ?, ?, ?, u.id, u.full_name, 'CHANGED', ? FROM users u WHERE u.id = ?
                """, UuidV7.generate(), tenantId, grantId, left(detail), operatorId)));
    }

    private static String left(String detail) {
        return detail.length() <= 500 ? detail : detail.substring(0, 500);
    }

    @Override
    public boolean isOpenAccount(UUID userId) {
        return !TenantContext.callAs(null, userId, () -> readOnly.execute(status ->
                jdbc.queryForList(OPEN_ACCOUNT, Integer.class, userId))).isEmpty();
    }

    @Override
    public Optional<Set<String>> resolvePermissions(UUID tenantId, UUID userId) {
        return TenantContext.callAs(tenantId, userId, () -> readOnly.execute(status -> {
            List<String> rows = jdbc.queryForList(PERMISSIONS, String.class, tenantId, userId);
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            Set<String> granted = new HashSet<>(
                    plan.narrow(tenantId, rows.stream().filter(Objects::nonNull).collect(Collectors.toSet())));
            jdbc.queryForList(PLATFORM_ADMIN, String.class, userId).forEach(role -> {
                granted.add(Permissions.PLATFORM_ADMIN);
                granted.addAll(Permissions.PLATFORM_ROLES.getOrDefault(role, Set.of()));
            });
            return Optional.of(granted);
        }));
    }

    /** What the current member's roles give them, whatever the plan allows. Runs in the caller's transaction. */
    public Set<String> rolePermissions() {
        return jdbc.queryForList(PERMISSIONS, String.class, TenantContext.getCurrentTenantId(),
                        TenantContext.getCurrentUserId()).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /** Must run with no open transaction. Row-level security admits a user's own memberships in any tenant. */
    public List<OrganizationSummary> organizationsOf(UUID userId) {
        return TenantContext.callAs(null, userId, () -> readOnly.execute(status -> jdbc.query(
                ORGANIZATIONS,
                (rs, row) -> new OrganizationSummary(rs.getObject("id", UUID.class), rs.getString("name")),
                userId)));
    }

    /** Active members of the current tenant who hold a permission. Runs in the caller's transaction. */
    public Set<UUID> activeMemberIds() {
        return Set.copyOf(jdbc.queryForList(
                "SELECT user_id FROM tenant_memberships WHERE tenant_id = ? AND status = 'ACTIVE'",
                UUID.class, TenantContext.getCurrentTenantId()));
    }

    /** @return the name of someone who is, or was, a member here */
    public Optional<String> memberName(UUID userId) {
        return jdbc.queryForList("""
                SELECT u.full_name FROM tenant_memberships m JOIN users u ON u.id = m.user_id
                WHERE m.tenant_id = ? AND m.user_id = ?
                """, String.class, TenantContext.getCurrentTenantId(), userId).stream().findFirst();
    }

    public Set<UUID> holdersOf(String permission) {
        return Set.copyOf(jdbc.queryForList(HOLDERS, UUID.class, TenantContext.getCurrentTenantId(), permission));
    }

    /** The address of an active member of the current tenant. Runs in the caller's transaction. */
    public Optional<MemberContact> activeMemberContact(UUID userId) {
        return jdbc.query(ACTIVE_EMAIL,
                (rs, row) -> new MemberContact(rs.getString(1), rs.getString(2)),
                TenantContext.getCurrentTenantId(), userId).stream().findFirst();
    }

    public record MemberContact(String email, String fullName) {
    }

    /**
     * Who someone is, for showing beside work they did: the current tenant's active members, and support operators
     * who made changes here, as "Support (name)". Not for choosing people; use {@link #activeMembers()} for that.
     */
    public Map<UUID, String> names() {
        Map<UUID, String> names = activeMembers();
        jdbc.query(SUPPORT_OPERATORS, rs -> {
            names.putIfAbsent(rs.getObject(1, UUID.class), "Support (" + rs.getString(2) + ")");
        }, TenantContext.getCurrentTenantId());
        return names;
    }

    /** Names of the current tenant's active members. Runs in the caller's transaction. */
    public Map<UUID, String> activeMembers() {
        Map<UUID, String> members = new LinkedHashMap<>();
        jdbc.query(ACTIVE_MEMBERS, rs -> {
            members.put(rs.getObject("id", UUID.class), rs.getString("full_name"));
        }, TenantContext.getCurrentTenantId());
        return members;
    }
}
