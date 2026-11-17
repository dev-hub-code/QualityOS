package com.qualityos.quality.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.quality.entity.NonConformance;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * An organisation's own names for the NCR statuses, such as "Quarantine" for containment. Only the names change:
 * every status keeps its place in the workflow and every rule tied to it.
 */
@Service
public class NcrStatusLabelService {

    public static final int MAX_LENGTH = 40;

    /**
     * @param standardLabel the name used when the organisation has not chosen one
     * @param label the organisation's own name, or null
     */
    public record StatusLabel(String status, String standardLabel, String label, boolean finished) {
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    NcrStatusLabelService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Every status in workflow order. */
    @Transactional(readOnly = true)
    public List<StatusLabel> labels() {
        Map<String, String> chosen = chosen();
        return Arrays.stream(NonConformance.Status.values())
                .map(status -> new StatusLabel(status.name(), standard(status), chosen.get(status.name()),
                        status.isFinished()))
                .toList();
    }

    /**
     * Replaces every name at once. A status left out, or given a blank name, goes back to its standard name.
     *
     * @throws BusinessRuleException if a name is too long, or two statuses would be shown alike
     */
    @Transactional
    public List<StatusLabel> save(Map<NonConformance.Status, String> requested) {
        Map<NonConformance.Status, String> labels = new EnumMap<>(NonConformance.Status.class);
        requested.forEach((status, label) -> {
            String trimmed = label == null ? "" : label.strip();
            if (status != null && !trimmed.isEmpty() && !trimmed.equals(standard(status))) {
                labels.put(status, trimmed);
            }
        });
        Map<String, NonConformance.Status> shown = new HashMap<>();
        for (NonConformance.Status status : NonConformance.Status.values()) {
            String label = labels.getOrDefault(status, standard(status));
            if (label.length() > MAX_LENGTH) {
                throw new BusinessRuleException("A status name can be at most " + MAX_LENGTH + " characters.");
            }
            NonConformance.Status clash = shown.putIfAbsent(label.toLowerCase(Locale.ROOT), status);
            if (clash != null) {
                throw new BusinessRuleException("Two statuses cannot share the name \"" + label + "\".");
            }
        }
        Map<String, String> previous = chosen();
        jdbc.update("DELETE FROM ncr_status_labels");
        labels.forEach((status, label) -> jdbc.update("""
                INSERT INTO ncr_status_labels (tenant_id, status, label, updated_by) VALUES (?, ?, ?, ?)
                """, TenantContext.getCurrentTenantId(), status.name(), label, TenantContext.getCurrentUserId()));
        Map<String, String> next = new HashMap<>();
        labels.forEach((status, label) -> next.put(status.name(), label));
        if (!previous.equals(next)) {
            audit.record("NCR_STATUS_LABELS_UPDATED", "Organization", TenantContext.getCurrentTenantId(),
                    Map.copyOf(previous), Map.copyOf(next));
        }
        return labels();
    }

    /** "UNDER_INVESTIGATION" becomes "Under investigation". */
    public static String standard(NonConformance.Status status) {
        String words = status.name().replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private Map<String, String> chosen() {
        Map<String, String> chosen = new HashMap<>();
        jdbc.query("SELECT status, label FROM ncr_status_labels",
                rows -> {
                    chosen.put(rows.getString("status"), rows.getString("label"));
                });
        return chosen;
    }
}
