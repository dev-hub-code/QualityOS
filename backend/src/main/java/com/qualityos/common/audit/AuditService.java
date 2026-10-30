package com.qualityos.common.audit;

import com.qualityos.common.config.CorrelationIdFilter;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.common.util.UuidV7;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/** Writes the append-only audit trail. Joins the caller's transaction, so an action and its event commit together. */
@Service
public class AuditService {

    private static final String INSERT = """
            INSERT INTO audit_logs (id, tenant_id, user_id, action, entity_type, entity_id, ip_address,
                                    correlation_id, previous_state, new_state)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
            """;

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public AuditService(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String entityType, Object entityId) {
        record(action, entityType, entityId, null, null);
    }

    /** @param previousState what changed, as a small map or record; never include secrets */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String entityType, Object entityId, Object previousState, Object newState) {
        jdbc.update(INSERT,
                UuidV7.generate(),
                TenantContext.findCurrentTenantId().orElse(null),
                TenantContext.findCurrentUserId().orElse(null),
                action,
                entityType,
                entityId == null ? null : entityId.toString(),
                clientAddress(),
                MDC.get(CorrelationIdFilter.MDC_KEY),
                toJson(previousState),
                toJson(newState));
    }

    private String toJson(Object state) {
        return state == null ? null : json.writeValueAsString(state);
    }

    private static String clientAddress() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest().getRemoteAddr()
                : null;
    }
}
