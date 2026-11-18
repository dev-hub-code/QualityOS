package com.qualityos.common.audit;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.qualityos.common.config.CorrelationIdFilter;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.common.util.UuidV7;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
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
                                    correlation_id, previous_state, new_state, support_grant_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
            """;

    private static final Set<String> BOOKKEEPING =
            Set.of("id", "version", "createdAt", "updatedAt", "createdBy", "updatedBy");

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final ApplicationEventPublisher events;

    public AuditService(JdbcTemplate jdbc, JsonMapper json, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.json = json;
        this.events = events;
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
                toJson(newState),
                supportGrant());
        TenantContext.findCurrentTenantId().map(TenantDataChanged::new).ifPresent(events::publishEvent);
    }

    /**
     * Records an edit as what it changed: each field, or each list of child lines, whose value differs, before and
     * after. Saving with nothing changed is still recorded, with no values.
     *
     * @param before the record as it was, from the same {@code snapshot} as {@code after}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordChange(
            String action, String entityType, Object entityId, Map<String, ?> before, Map<String, ?> after) {
        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> now = new LinkedHashMap<>();
        Set<String> keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            Object old = before.get(key);
            Object current = after.get(key);
            if (!same(old, current)) {
                was.put(key, old);
                now.put(key, current);
            }
        }
        record(action, entityType, entityId, was.isEmpty() ? null : was, now.isEmpty() ? null : now);
    }

    /**
     * A record as the screen sees it, for {@link #recordChange}: the response it is shown with, less the bookkeeping
     * every record has. Child lines in the response come along, so a change to one shows as the list before and after.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> state(Object response) {
        Map<String, Object> state = new LinkedHashMap<>(json.convertValue(response, Map.class));
        BOOKKEEPING.forEach(state::remove);
        return state;
    }

    /** Numbers compare by value, also inside child lines, so that 5 and 5.00 are the same. */
    private static boolean same(Object a, Object b) {
        return Objects.equals(normal(a), normal(b));
    }

    private static Object normal(Object value) {
        if (value instanceof BigDecimal number) {
            return number.signum() == 0 ? BigDecimal.ZERO : number.stripTrailingZeros();
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(key, normal(item)));
            return copy;
        }
        if (value instanceof java.util.Collection<?> items) {
            return items.stream().map(AuditService::normal).toList();
        }
        return value;
    }

    private String toJson(Object state) {
        return state == null ? null : json.writeValueAsString(state);
    }

    /** The support grant the current request makes changes under, if it is a support session's. */
    private static Object supportGrant() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest().getAttribute(
                        com.qualityos.common.security.TenantJwtAuthenticationConverter.SUPPORT_GRANT_ATTRIBUTE)
                : null;
    }

    private static String clientAddress() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest().getRemoteAddr()
                : null;
    }
}
