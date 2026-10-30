package com.qualityos.common.audit;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.security.Permissions;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-logs")
@Tag(name = "Audit trail")
public class AuditLogController {

    private final JdbcTemplate jdbc;

    AuditLogController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Row-level security limits both queries to the current tenant. */
    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.AUDIT_LOG_VIEW + "')")
    @Transactional(readOnly = true)
    public ApiResponse<PageResponse<AuditLogEntry>> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        long total = jdbc.queryForObject("SELECT count(*) FROM audit_logs", Long.class);
        List<AuditLogEntry> items = jdbc.query("""
                SELECT id, user_id, action, entity_type, entity_id, occurred_at, ip_address, correlation_id
                FROM audit_logs ORDER BY occurred_at DESC, id DESC LIMIT ? OFFSET ?
                """,
                (rs, row) -> new AuditLogEntry(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("action"),
                        rs.getString("entity_type"),
                        rs.getString("entity_id"),
                        rs.getObject("occurred_at", Timestamp.class).toInstant(),
                        rs.getString("ip_address"),
                        rs.getString("correlation_id")),
                size, (long) page * size);
        return ApiResponse.ok(new PageResponse<>(items, page, size, total, (int) Math.ceil((double) total / size)));
    }

    public record AuditLogEntry(
            UUID id,
            UUID userId,
            String action,
            String entityType,
            String entityId,
            Instant occurredAt,
            String ipAddress,
            String correlationId) {
    }
}
