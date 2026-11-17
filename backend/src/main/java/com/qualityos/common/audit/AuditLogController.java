package com.qualityos.common.audit;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.security.Permissions;
import com.qualityos.common.tenant.TenantContext;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * An organisation's own audit trail: what its members did, newest first. Row-level security limits every query to
 * the current tenant, plus its members' account-level events (verification, password reset, failed sign-ins) from the
 * day they joined, which belong to no tenant.
 */
@RestController
@RequestMapping("/api/v1/audit-logs")
@Tag(name = "Audit trail")
public class AuditLogController {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Kolkata");
    private static final String FROM = """
            FROM audit_logs a
            LEFT JOIN users u ON u.id = a.user_id
            WHERE (CAST(:action AS text) IS NULL OR a.action = :action)
              AND (CAST(:from AS timestamptz) IS NULL OR a.occurred_at >= :from)
              AND (CAST(:to AS timestamptz) IS NULL OR a.occurred_at < :to)
              AND (CAST(:pattern AS text) IS NULL OR u.full_name ILIKE :pattern OR u.email ILIKE :pattern
                   OR a.entity_id ILIKE :pattern OR a.entity_type ILIKE :pattern OR a.correlation_id ILIKE :pattern)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;

    AuditLogController(NamedParameterJdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * @param q matches the actor's name or email, the target's type or ID, or the correlation ID
     * @param action one action code, such as {@code FACILITY_CREATED}
     * @param from the first day shown, in the organisation's time zone
     * @param to the last day shown, in the organisation's time zone
     */
    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.AUDIT_LOG_VIEW + "')")
    @Transactional(readOnly = true)
    public ApiResponse<PageResponse<AuditLogEntry>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        ZoneId zone = zone();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("action", blankToNull(action))
                .addValue("pattern", pattern(q))
                .addValue("from", from == null ? null : Timestamp.from(from.atStartOfDay(zone).toInstant()))
                .addValue("to", to == null ? null : Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant()))
                .addValue("limit", size)
                .addValue("offset", (long) page * size);
        long total = jdbc.queryForObject("SELECT count(*) " + FROM, params, Long.class);
        List<AuditLogEntry> items = jdbc.query("""
                SELECT a.id, a.user_id, u.full_name, a.action, a.entity_type, a.entity_id, a.occurred_at,
                       a.ip_address, a.correlation_id, a.previous_state::text AS before, a.new_state::text AS after,
                       a.new_state ->> 'reason' AS reason,
                       (a.support_grant_id IS NOT NULL OR a.action = 'SUPPORT_SESSION_STARTED') AS by_support
                """ + FROM + " ORDER BY a.occurred_at DESC, a.id DESC LIMIT :limit OFFSET :offset",
                params,
                (rs, row) -> new AuditLogEntry(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getBoolean("by_support") && rs.getString("full_name") != null
                                ? "Support (" + rs.getString("full_name") + ")" : rs.getString("full_name"),
                        rs.getString("action"),
                        rs.getString("entity_type"),
                        rs.getString("entity_id"),
                        rs.getObject("occurred_at", Timestamp.class).toInstant(),
                        rs.getString("ip_address"),
                        rs.getString("correlation_id"),
                        rs.getString("reason"),
                        parse(rs.getString("before")),
                        parse(rs.getString("after")),
                        rs.getBoolean("by_support")));
        return ApiResponse.ok(new PageResponse<>(items, page, size, total, (int) Math.ceil((double) total / size)));
    }

    /** The organisation's time zone, so that a day filter means the day its people worked. */
    private ZoneId zone() {
        try {
            String zone = jdbc.queryForObject("SELECT timezone FROM tenants WHERE id = :tenant",
                    Map.of("tenant", TenantContext.getCurrentTenantId()), String.class);
            return zone == null ? DEFAULT_ZONE : ZoneId.of(zone);
        } catch (DataAccessException | java.time.DateTimeException e) {
            return DEFAULT_ZONE;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** A contains-match, with the pattern characters in the search escaped. */
    private static String pattern(String q) {
        String term = blankToNull(q);
        return term == null ? null
                : "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private Map<String, Object> parse(String state) {
        if (state == null) {
            return null;
        }
        try {
            return json.readValue(state, new TypeReference<>() { });
        } catch (JacksonException e) {
            return Map.of("value", state);
        }
    }

    /** {@code actor} is null for an event the system raised on its own, such as a scheduled job. */
    public record AuditLogEntry(
            UUID id,
            UUID userId,
            String actor,
            String action,
            String entityType,
            String entityId,
            Instant occurredAt,
            String ipAddress,
            String correlationId,
            String reason,
            Map<String, Object> before,
            Map<String, Object> after,
            /* Done by ProductionOS support in a session the organisation granted, not by a member. */
            boolean bySupport) {
    }
}
