package com.qualityos.identity.service;

import com.qualityos.common.security.TenantAccessResolver;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.identity.dto.AuthDtos.OrganizationSummary;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private final JdbcTemplate jdbc;
    private final TransactionTemplate readOnly;

    TenantAccessService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    @Override
    public Optional<Set<String>> resolvePermissions(UUID tenantId, UUID userId) {
        List<String> rows = TenantContext.callAs(tenantId, userId, () -> readOnly.execute(status ->
                jdbc.queryForList(PERMISSIONS, String.class, tenantId, userId)));
        return rows.isEmpty()
                ? Optional.empty()
                : Optional.of(rows.stream().filter(Objects::nonNull).collect(Collectors.toSet()));
    }

    /** Must run with no open transaction. Row-level security admits a user's own memberships in any tenant. */
    public List<OrganizationSummary> organizationsOf(UUID userId) {
        return TenantContext.callAs(null, userId, () -> readOnly.execute(status -> jdbc.query(
                ORGANIZATIONS,
                (rs, row) -> new OrganizationSummary(rs.getObject("id", UUID.class), rs.getString("name")),
                userId)));
    }
}
