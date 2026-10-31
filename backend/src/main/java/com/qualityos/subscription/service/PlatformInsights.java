package com.qualityos.subscription.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.organization.dto.OrganizationResponse;
import com.qualityos.organization.service.OrganizationService;
import com.qualityos.subscription.dto.SubscriptionDtos.OrganisationActivity;
import com.qualityos.subscription.dto.SubscriptionDtos.OrganisationDetail;
import com.qualityos.subscription.dto.SubscriptionDtos.OrganisationFilter;
import com.qualityos.subscription.dto.SubscriptionDtos.OrganisationList;
import com.qualityos.subscription.dto.SubscriptionDtos.OrganisationRow;
import com.qualityos.subscription.dto.SubscriptionDtos.OrganisationUsage;
import com.qualityos.subscription.dto.SubscriptionDtos.PlatformDashboard;
import com.qualityos.subscription.dto.SubscriptionDtos.TenantSubscription;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads across organisations. Finding and counting happen in functions that check for an administrator themselves.
 * Everything about one organisation is then read under that organisation's own tenant context. Must run with no open
 * transaction.
 */
@Service
public class PlatformInsights {

    public static final int MAX_EXPORT = 5_000;

    private static final int MAX_DAYS = 731;
    private static final int DEFAULT_DAYS = 30;
    private static final int RECENT_ACTIVITY = 20;
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final Set<String> STATES = Set.of("TRIAL", "ACTIVE", "GRACE", "READ_ONLY");
    private static final Set<String> SORTS = Set.of("NAME", "NEWEST", "ENDING");
    private static final String PAGE = "SELECT * FROM platform_organisation_page(?, ?, ?, ?, ?, ?, ?, ?, ?)";
    /** Names the organisation, because row-level security also shows an administrator their own memberships. */
    private static final String CONTACT = """
            SELECT u.email FROM tenant_memberships m JOIN users u ON u.id = m.user_id
            WHERE m.tenant_id = ? ORDER BY m.created_at LIMIT 1
            """;
    private static final String USAGE = """
            SELECT (SELECT count(*) FROM inspections), (SELECT count(*) FROM non_conformances),
                   (SELECT count(*) FROM corrective_actions),
                   (SELECT count(*) FROM attachments WHERE deleted_at IS NULL),
                   (SELECT COALESCE(sum(size_bytes), 0) FROM attachments WHERE deleted_at IS NULL)
            """;
    private static final String ACTIVITY = """
            SELECT action, entity_type, occurred_at FROM audit_logs ORDER BY occurred_at DESC LIMIT ?
            """;

    private final SubscriptionService subscriptions;
    private final OrganizationService organizations;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final TransactionTemplate tx;

    PlatformInsights(
            SubscriptionService subscriptions,
            OrganizationService organizations,
            AuditService audit,
            JdbcTemplate jdbc,
            JsonMapper json,
            PlatformTransactionManager transactionManager) {
        this.subscriptions = subscriptions;
        this.organizations = organizations;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public PlatformDashboard dashboard(LocalDate from, LocalDate to) {
        LocalDate end = to == null ? LocalDate.now(ZONE) : to;
        LocalDate start = from == null ? end.minusDays(DEFAULT_DAYS - 1) : from;
        requirePeriod(start, end);
        String figures = tx.execute(status -> jdbc.queryForObject(
                "SELECT platform_dashboard(?, ?, ?)::text", String.class, opening(start), opening(end.plusDays(1)),
                ZONE.getId()));
        Map<String, Object> merged = json.readValue(figures, new tools.jackson.core.type.TypeReference<>() { });
        merged.put("from", start.toString());
        merged.put("to", end.toString());
        return json.convertValue(merged, PlatformDashboard.class);
    }

    private static void requirePeriod(LocalDate start, LocalDate end) {
        if (start.isAfter(end)) {
            throw new BusinessRuleException("The start date is after the end date.");
        }
        if (ChronoUnit.DAYS.between(start, end) > MAX_DAYS) {
            throw new BusinessRuleException("Choose a period of two years or less.");
        }
    }

    private static OffsetDateTime opening(LocalDate day) {
        return day == null ? null : day.atStartOfDay(ZONE).toOffsetDateTime();
    }

    public OrganisationList organisations(OrganisationFilter filter, int page, int size) {
        List<Long> total = new ArrayList<>();
        List<OrganisationRow> rows = rows(filter, size, page * size, total);
        return new OrganisationList(total.isEmpty() ? 0 : total.getFirst().intValue(), rows.stream()
                .map(row -> inside(row.tenantId(), () -> new TenantSubscription(row.tenantId(), row.name(),
                        subscriptions.current(),
                        subscriptions.invoices().stream().filter(invoice -> invoice.status().equals("ISSUED")).toList(),
                        row.gstin(), row.contactEmail(), row.registeredAt())))
                .toList());
    }

    /** Leaves a trace, because the file carries contact details out of the platform. */
    public List<OrganisationRow> export(OrganisationFilter filter) {
        List<OrganisationRow> rows = rows(filter, MAX_EXPORT, 0, new ArrayList<>());
        tx.executeWithoutResult(status -> audit.record(
                "ORGANISATIONS_EXPORTED", "Organisation", null, null, Map.of("rows", rows.size())));
        return rows;
    }

    private List<OrganisationRow> rows(OrganisationFilter filter, int limit, int offset, List<Long> total) {
        if (filter.state() != null && !STATES.contains(filter.state())) {
            throw new BusinessRuleException("Unknown state.");
        }
        if (filter.sort() != null && !SORTS.contains(filter.sort())) {
            throw new BusinessRuleException("Unknown sort.");
        }
        if (filter.registeredFrom() != null && filter.registeredTo() != null) {
            requirePeriodOrder(filter.registeredFrom(), filter.registeredTo());
        }
        return tx.execute(status -> jdbc.query(PAGE, (rows, row) -> {
            total.clear();
            total.add(rows.getLong("total"));
            return row(rows);
        }, StringUtils.hasText(filter.q()) ? filter.q().trim() : null, filter.plan(), filter.state(), filter.unpaid(),
                opening(filter.registeredFrom()),
                filter.registeredTo() == null ? null : opening(filter.registeredTo().plusDays(1)),
                filter.sort(), limit, offset));
    }

    private static void requirePeriodOrder(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new BusinessRuleException("The start date is after the end date.");
        }
    }

    private static OrganisationRow row(ResultSet rows) throws SQLException {
        return new OrganisationRow(rows.getObject("tenant_id", UUID.class), rows.getString("name"),
                rows.getString("gstin"), rows.getString("contact_email"), rows.getString("plan_name"),
                rows.getString("state"), rows.getTimestamp("ends_at").toInstant(),
                rows.getTimestamp("registered_at").toInstant(), rows.getLong("open_invoices"));
    }

    /** Figures and event names only. The content of an organisation's quality records stays out of this page. */
    public OrganisationDetail organisation(UUID tenant) {
        Boolean known = tx.execute(status -> jdbc.queryForList("SELECT platform_tenant_ids()", UUID.class).contains(tenant));
        if (!Boolean.TRUE.equals(known)) {
            throw new ResourceNotFoundException("Organisation", tenant.toString());
        }
        return inside(tenant, () -> {
            OrganizationResponse organization = organizations.getCurrent();
            Timestamp registered = jdbc.queryForObject("SELECT created_at FROM tenants WHERE id = ?", Timestamp.class, tenant);
            OrganisationUsage usage = jdbc.queryForObject(USAGE, (rows, row) -> new OrganisationUsage(
                    rows.getLong(1), rows.getLong(2), rows.getLong(3), rows.getLong(4), rows.getLong(5)));
            List<OrganisationActivity> activity = jdbc.query(ACTIVITY, (rows, row) -> new OrganisationActivity(
                    rows.getString(1), rows.getString(2), rows.getTimestamp(3).toInstant()), RECENT_ACTIVITY);
            List<String> contact = jdbc.queryForList(CONTACT, String.class, tenant);
            return new OrganisationDetail(tenant, organization.name(), organization.legalName(), organization.gstin(),
                    contact.isEmpty() ? null : contact.getFirst(), organization.countryCode(), organization.timezone(),
                    registered.toInstant(), subscriptions.current(), usage, subscriptions.invoices(),
                    subscriptions.payments(), activity);
        });
    }

    private <T> T inside(UUID tenant, java.util.function.Supplier<T> work) {
        UUID admin = TenantContext.getCurrentUserId();
        return TenantContext.callAs(tenant, admin, () -> tx.execute(status -> work.get()));
    }
}
