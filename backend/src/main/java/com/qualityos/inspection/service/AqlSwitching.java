package com.qualityos.inspection.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.common.util.UuidV7;
import com.qualityos.inspection.entity.AqlTable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The switching rules of ISO 2859-1 / ANSI Z1.4 for a series of lots: one template's lots from one supplier, or its
 * production lots. Only a lot's original inspection counts; a return or a repeat inspection of the same lot does not.
 *
 * <ul>
 *   <li>Normal to tightened: two of the last five lots (or fewer) rejected.</li>
 *   <li>Tightened to normal: five lots in a row accepted.</li>
 *   <li>Tightened to discontinued: five lots rejected while tightened. Nothing more is inspected by AQL in the
 *       series until someone resumes it, on tightened inspection.</li>
 *   <li>Normal to reduced: a switching score of 30 or more, and someone's decision that production is steady.</li>
 *   <li>Reduced to normal: a lot rejected, or accepted with more than its acceptance number nonconforming.</li>
 * </ul>
 *
 * <p>The switching score starts at zero on normal inspection. A lot accepted under a plan with Ac of 2 or more adds 3
 * if it would also have been accepted at the next tighter AQL, and otherwise resets the score; under Ac 0 or 1 an
 * accepted lot adds 2. A rejected lot resets it.
 */
@Service
public class AqlSwitching {

    /** Where a series stands. */
    public enum State { NORMAL, TIGHTENED, REDUCED, DISCONTINUED }

    public static final int REDUCED_SCORE = 30;
    private static final int WINDOW = 5;
    private static final UUID NO_SUPPLIER = new UUID(0, 0);

    /** @param supplierId null for production lots */
    public record Series(UUID templateId, UUID supplierId, State state, String recentResults, int switchingScore,
            int tightenedAcceptedInRow, int tightenedRejected) {

        public boolean mayReduce() {
            return state == State.NORMAL && switchingScore >= REDUCED_SCORE;
        }
    }

    /** A series as shown: the supplier named, and whether reduced inspection may start. */
    public record SeriesView(UUID supplierId, String supplierName, State state, String recentResults,
            int switchingScore, boolean mayReduce, int tightenedAcceptedInRow, int tightenedRejected) {
    }

    /** A person's switch, with their reason. */
    public record SwitchRequest(
            UUID supplierId,
            @jakarta.validation.constraints.NotNull State state,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    /**
     * What decided a lot, from its inspection.
     *
     * @param acceptanceNumber the one that decided the lot: under double sampling, the second sample's once it was drawn
     * @param decidedByFirstSample under double sampling, whether the first sample decided the lot; null under single
     */
    public record Lot(UUID templateId, UUID supplierId, long lotSize, AqlTable.Level level, BigDecimal aql,
            AqlTable.Severity severity, int acceptanceNumber, int nonconforming, boolean accepted,
            Boolean decidedByFirstSample) {

        public Lot(UUID templateId, UUID supplierId, long lotSize, AqlTable.Level level, BigDecimal aql,
                AqlTable.Severity severity, int acceptanceNumber, int nonconforming, boolean accepted) {
            this(templateId, supplierId, lotSize, level, aql, severity, acceptanceNumber, nonconforming, accepted, null);
        }
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final com.qualityos.supplier.service.SupplierService suppliers;

    AqlSwitching(JdbcTemplate jdbc, AuditService audit, com.qualityos.supplier.service.SupplierService suppliers) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.suppliers = suppliers;
    }

    /** Every series of a template that has had a lot decided or a switch made, production lots first. */
    @Transactional(readOnly = true)
    public List<SeriesView> views(UUID templateId) {
        List<Series> all = forTemplate(templateId);
        var names = suppliers.references(all.stream().map(Series::supplierId).filter(Objects::nonNull).toList());
        return all.stream().map(series -> new SeriesView(series.supplierId(),
                series.supplierId() == null ? null : names.get(series.supplierId()).name(), series.state(),
                series.recentResults(), series.switchingScore(), series.mayReduce(), series.tightenedAcceptedInRow(),
                series.tightenedRejected())).toList();
    }

    /** The severity a new lot of the series is inspected at, refusing a discontinued series. */
    @Transactional(readOnly = true)
    public AqlTable.Severity severityFor(UUID templateId, UUID supplierId) {
        State state = find(templateId, supplierId).map(Series::state).orElse(State.NORMAL);
        if (state == State.DISCONTINUED) {
            throw new BusinessRuleException("Acceptance inspection under this template is discontinued for "
                    + (supplierId == null ? "these production lots" : "this supplier")
                    + " after five rejected lots on tightened inspection. Once quality has improved, someone who "
                    + "manages templates can resume it, on tightened inspection.");
        }
        return AqlTable.Severity.valueOf(state.name());
    }

    /** Whether no other inspection of the same lot under the same template was decided before this one. */
    @Transactional(readOnly = true)
    public boolean isOriginal(UUID inspectionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT NOT EXISTS (
                    SELECT 1
                    FROM inspections i
                    JOIN inspection_template_versions iv ON iv.id = i.template_version_id
                    JOIN inspections o ON o.id <> i.id AND o.approved_at IS NOT NULL AND o.return_item_id IS NULL
                        AND (o.receipt_item_id = i.receipt_item_id OR o.production_batch_id = i.production_batch_id)
                    JOIN inspection_template_versions ov ON ov.id = o.template_version_id
                        AND ov.template_id = iv.template_id
                    WHERE i.id = ? AND o.approved_at <= i.approved_at)
                """, Boolean.class, inspectionId));
    }

    @Transactional(readOnly = true)
    public UUID templateOf(UUID templateVersionId) {
        return jdbc.queryForObject("SELECT template_id FROM inspection_template_versions WHERE id = ?", UUID.class,
                templateVersionId);
    }

    @Transactional(readOnly = true)
    public List<Series> forTemplate(UUID templateId) {
        return jdbc.query("""
                SELECT * FROM aql_switching_states WHERE template_id = ? ORDER BY supplier_id NULLS FIRST
                """, (rows, row) -> series(rows), templateId);
    }

    /** Counts a decided lot. Joins the approval's transaction, and locks the series so lots count one at a time. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Lot lot) {
        Series before = lock(lot.templateId(), lot.supplierId());
        Series after = switch (before.state()) {
            case NORMAL -> onNormal(before, lot);
            case TIGHTENED -> onTightened(before, lot);
            case REDUCED -> lot.accepted() && lot.nonconforming() <= lot.acceptanceNumber()
                    ? before : fresh(before, State.NORMAL);
            // Lots started before the series was discontinued still count, and change nothing.
            case DISCONTINUED -> before;
        };
        save(after);
        if (after.state() != before.state()) {
            audit.record("AQL_SWITCHED", "InspectionTemplate", lot.templateId(), null, Map.of(
                    "from", before.state(), "to", after.state(), "supplierId", String.valueOf(lot.supplierId()),
                    "reason", "Switching rule"));
        }
    }

    /**
     * A person's decision: reduced inspection once the score allows it, a return to normal (such as when production
     * becomes irregular), tightened inspection at any time, or resuming a discontinued series on tightened.
     */
    @Transactional
    public Series decide(UUID templateId, UUID supplierId, State target, String reason) {
        if (supplierId != null && suppliers.references(List.of(supplierId)).isEmpty()) {
            throw new BusinessRuleException("This supplier does not exist.");
        }
        Series before = lock(templateId, supplierId);
        if (target == before.state()) {
            throw new BusinessRuleException("The series is already on " + target.name().toLowerCase() + " inspection.");
        }
        switch (target) {
            case REDUCED -> {
                if (!before.mayReduce()) {
                    throw new BusinessRuleException("Reduced inspection needs a switching score of at least "
                            + REDUCED_SCORE + " on normal inspection.");
                }
            }
            case NORMAL -> {
                if (before.state() == State.DISCONTINUED) {
                    throw new BusinessRuleException("A discontinued series resumes on tightened inspection.");
                }
            }
            case DISCONTINUED -> throw new BusinessRuleException("A series is discontinued only by its results.");
            case TIGHTENED -> {
            }
        }
        Series after = fresh(before, target);
        save(after);
        audit.record("AQL_SWITCHED", "InspectionTemplate", templateId, null, Map.of(
                "from", before.state(), "to", target, "supplierId", String.valueOf(supplierId), "reason", reason.trim()));
        return after;
    }

    private static Series onNormal(Series series, Lot lot) {
        String recent = (series.recentResults() + (lot.accepted() ? "A" : "R"));
        recent = recent.substring(Math.max(0, recent.length() - WINDOW));
        if (recent.chars().filter(result -> result == 'R').count() >= 2) {
            return fresh(series, State.TIGHTENED);
        }
        return new Series(series.templateId(), series.supplierId(), State.NORMAL, recent,
                score(series.switchingScore(), lot), 0, 0);
    }

    static int score(int score, Lot lot) {
        if (!lot.accepted()) {
            return 0;
        }
        // Under double sampling (ISO 2859-1): three for a lot accepted on its first sample, otherwise back to 0.
        if (lot.decidedByFirstSample() != null) {
            return lot.decidedByFirstSample() ? score + 3 : 0;
        }
        if (lot.acceptanceNumber() < 2) {
            return score + 2;
        }
        BigDecimal tighter = AqlTable.tighter(lot.aql());
        boolean stillAccepted = tighter != null && lot.nonconforming()
                <= AqlTable.plan(lot.lotSize(), lot.level(), tighter, AqlTable.Severity.NORMAL).acceptanceNumber();
        return stillAccepted ? score + 3 : 0;
    }

    private static Series onTightened(Series series, Lot lot) {
        if (lot.accepted()) {
            int inRow = series.tightenedAcceptedInRow() + 1;
            return inRow >= WINDOW ? fresh(series, State.NORMAL) : new Series(series.templateId(),
                    series.supplierId(), State.TIGHTENED, "", 0, inRow, series.tightenedRejected());
        }
        int rejected = series.tightenedRejected() + 1;
        return rejected >= WINDOW ? fresh(series, State.DISCONTINUED) : new Series(series.templateId(),
                series.supplierId(), State.TIGHTENED, "", 0, 0, rejected);
    }

    /** A series starting over on another severity. */
    private static Series fresh(Series series, State state) {
        return new Series(series.templateId(), series.supplierId(), state, "", 0, 0, 0);
    }

    private java.util.Optional<Series> find(UUID templateId, UUID supplierId) {
        return jdbc.query("""
                SELECT * FROM aql_switching_states
                WHERE template_id = ? AND COALESCE(supplier_id, ?) = ?
                """, (rows, row) -> series(rows), templateId, NO_SUPPLIER, Objects.requireNonNullElse(supplierId, NO_SUPPLIER))
                .stream().findFirst();
    }

    private Series lock(UUID templateId, UUID supplierId) {
        jdbc.update("""
                INSERT INTO aql_switching_states (id, tenant_id, template_id, supplier_id) VALUES (?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, UuidV7.generate(), TenantContext.getCurrentTenantId(), templateId, supplierId);
        return jdbc.query("""
                SELECT * FROM aql_switching_states
                WHERE template_id = ? AND COALESCE(supplier_id, ?) = ? FOR UPDATE
                """, (rows, row) -> series(rows), templateId, NO_SUPPLIER,
                Objects.requireNonNullElse(supplierId, NO_SUPPLIER)).getFirst();
    }

    private void save(Series series) {
        jdbc.update("""
                UPDATE aql_switching_states
                SET severity = ?, recent_results = ?, switching_score = ?, tightened_accepted_in_row = ?,
                    tightened_rejected = ?, updated_at = now()
                WHERE template_id = ? AND COALESCE(supplier_id, ?) = ?
                """, series.state().name(), series.recentResults(), series.switchingScore(),
                series.tightenedAcceptedInRow(), series.tightenedRejected(), series.templateId(), NO_SUPPLIER,
                Objects.requireNonNullElse(series.supplierId(), NO_SUPPLIER));
    }

    private static Series series(java.sql.ResultSet rows) throws java.sql.SQLException {
        return new Series(rows.getObject("template_id", UUID.class), rows.getObject("supplier_id", UUID.class),
                State.valueOf(rows.getString("severity")), rows.getString("recent_results"),
                rows.getInt("switching_score"), rows.getInt("tightened_accepted_in_row"),
                rows.getInt("tightened_rejected"));
    }
}
