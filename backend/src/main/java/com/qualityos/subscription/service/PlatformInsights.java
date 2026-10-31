package com.qualityos.subscription.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.organization.dto.OrganizationResponse;
import com.qualityos.organization.service.OrganizationService;
import com.qualityos.subscription.dto.SubscriptionDtos.InvoiceFilter;
import com.qualityos.subscription.dto.SubscriptionDtos.InvoiceList;
import com.qualityos.subscription.dto.SubscriptionDtos.InvoiceResponse;
import com.qualityos.subscription.dto.SubscriptionDtos.OrganisationActivity;
import com.qualityos.subscription.dto.SubscriptionDtos.OrganisationDetail;
import com.qualityos.subscription.dto.SubscriptionDtos.OrganisationFilter;
import com.qualityos.subscription.dto.SubscriptionDtos.OrganisationList;
import com.qualityos.subscription.dto.SubscriptionDtos.OrganisationRow;
import com.qualityos.subscription.dto.SubscriptionDtos.OrganisationUsage;
import com.qualityos.subscription.dto.SubscriptionDtos.PaymentList;
import com.qualityos.subscription.dto.SubscriptionDtos.PlatformDashboard;
import com.qualityos.subscription.dto.SubscriptionDtos.PlatformInvoice;
import com.qualityos.subscription.dto.SubscriptionDtos.PlatformPayment;
import com.qualityos.subscription.dto.SubscriptionDtos.TenantSubscription;
import com.qualityos.subscription.dto.SubscriptionDtos.TrialSummary;
import com.qualityos.subscription.entity.SubscriptionInvoice;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
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
    private static final Set<String> STATES = Set.of("TRIAL", "ACTIVE", "GRACE", "READ_ONLY", "SUSPENDED", "CANCELLED");
    private static final Set<String> SORTS = Set.of("NAME", "NEWEST", "ENDING");
    private static final String PAGE = "SELECT * FROM platform_organisation_page(?, ?, ?, ?, ?, ?, ?, ?, ?)";
    /** Names the organisation, because row-level security also shows an administrator their own memberships. */
    private static final String INVOICES = "SELECT * FROM platform_invoice_page(?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String PAYMENTS = "SELECT * FROM platform_payment_page(?, ?, ?, ?, ?, ?)";
    private static final Set<String> INVOICE_STATES = Set.of("ISSUED", "PAID", "VOID");
    private static final Set<String> PAYMENT_STATES = Set.of("SUCCEEDED", "FAILED");
    private static final String CONTACT = """
            SELECT u.email FROM tenant_memberships m JOIN users u ON u.id = m.user_id
            WHERE m.tenant_id = ? ORDER BY m.created_at LIMIT 1
            """;
    private static final String TENANT = "SELECT created_at, suspended_at, suspension_reason FROM tenants WHERE id = ?";
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
                        row.gstin(), row.contactEmail(), row.registeredAt(), row.subscriptionId(), row.suspendedAt(),
                        row.nextDue())))
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
                rows.getObject("subscription_id", UUID.class), rows.getString("gstin"), rows.getString("contact_email"),
                rows.getString("plan_code"),
                rows.getString("plan_name"), rows.getString("state"), rows.getString("billing_cycle"),
                rows.getTimestamp("ends_at").toInstant(), instant(rows, "cancelled_at"), instant(rows, "suspended_at"),
                rows.getTimestamp("registered_at").toInstant(), rows.getLong("open_invoices"),
                rows.getObject("next_due", LocalDate.class));
    }

    private static Instant instant(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public InvoiceList invoices(InvoiceFilter filter, int page, int size) {
        List<Long> total = new ArrayList<>();
        List<PlatformInvoice> rows = invoiceRows(filter, size, page * size, total);
        return new InvoiceList(total.isEmpty() ? 0 : total.getFirst().intValue(), rows);
    }

    /** Leaves a trace, because the file carries billing figures out of the platform. */
    public List<PlatformInvoice> exportInvoices(InvoiceFilter filter) {
        List<PlatformInvoice> rows = invoiceRows(filter, MAX_EXPORT, 0, new ArrayList<>());
        tx.executeWithoutResult(status -> audit.record(
                "INVOICES_EXPORTED", "SubscriptionInvoice", null, null, Map.of("rows", rows.size())));
        return rows;
    }

    private List<PlatformInvoice> invoiceRows(InvoiceFilter filter, int limit, int offset, List<Long> total) {
        if (filter.status() != null && !INVOICE_STATES.contains(filter.status())) {
            throw new BusinessRuleException("Unknown status.");
        }
        return tx.execute(status -> jdbc.query(INVOICES, (rows, row) -> {
            total.clear();
            total.add(rows.getLong("total"));
            return new PlatformInvoice(rows.getObject("id", UUID.class), rows.getObject("tenant_id", UUID.class),
                    rows.getString("organisation"), rows.getString("invoice_number"), rows.getString("plan_name"),
                    rows.getString("billing_cycle"), instant(rows, "period_starts_at"), instant(rows, "period_ends_at"),
                    rows.getBigDecimal("amount"), rows.getBigDecimal("tax_amount"), rows.getBigDecimal("invoice_total"),
                    rows.getBigDecimal("paid"), rows.getBigDecimal("outstanding"), rows.getString("status"),
                    rows.getObject("issued_on", LocalDate.class), rows.getObject("due_on", LocalDate.class));
        }, text(filter.q()), filter.status(), filter.overdue(), filter.from(), filter.to(), filter.min(), filter.max(),
                limit, offset));
    }

    public PaymentList payments(String q, String state, LocalDate from, LocalDate to, int page, int size) {
        if (state != null && !PAYMENT_STATES.contains(state)) {
            throw new BusinessRuleException("Unknown status.");
        }
        List<Long> total = new ArrayList<>();
        List<PlatformPayment> rows = tx.execute(status -> jdbc.query(PAYMENTS, (result, row) -> {
            total.clear();
            total.add(result.getLong("total"));
            return new PlatformPayment(result.getObject("id", UUID.class), result.getObject("tenant_id", UUID.class),
                    result.getString("organisation"), result.getObject("invoice_id", UUID.class),
                    result.getString("invoice_number"), result.getString("provider"), result.getString("reference"),
                    result.getString("method"), result.getBigDecimal("amount"), result.getString("status"),
                    result.getString("failure_reason"), result.getObject("paid_on", LocalDate.class),
                    result.getString("notes"), result.getString("recorded_by"), instant(result, "recorded_at"));
        }, text(q), state, from, to, size, page * size));
        return new PaymentList(total.isEmpty() ? 0 : total.getFirst().intValue(), rows);
    }

    public TrialSummary trialSummary() {
        String figures = tx.execute(status -> jdbc.queryForObject(
                "SELECT platform_trial_summary(?)::text", String.class, ZONE.getId()));
        return json.readValue(figures, TrialSummary.class);
    }

    private static String text(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /** For a platform administrator's copy, read under the organisation's own tenant context. */
    public SubscriptionInvoice invoice(UUID tenant, UUID id) {
        return inside(tenant, () -> subscriptions.invoice(id));
    }

    public InvoiceResponse invoiceDetail(UUID tenant, UUID id) {
        return SubscriptionService.toResponse(invoice(tenant, id));
    }

    /** Figures and event names only. The content of an organisation's quality records stays out of this page. */
    public OrganisationDetail organisation(UUID tenant) {
        Boolean known = tx.execute(status -> jdbc.queryForList("SELECT platform_tenant_ids()", UUID.class).contains(tenant));
        if (!Boolean.TRUE.equals(known)) {
            throw new ResourceNotFoundException("Organisation", tenant.toString());
        }
        return inside(tenant, () -> {
            OrganizationResponse organization = organizations.getCurrent();
            OrganisationUsage usage = jdbc.queryForObject(USAGE, (rows, row) -> new OrganisationUsage(
                    rows.getLong(1), rows.getLong(2), rows.getLong(3), rows.getLong(4), rows.getLong(5)));
            List<OrganisationActivity> activity = jdbc.query(ACTIVITY, (rows, row) -> new OrganisationActivity(
                    rows.getString(1), rows.getString(2), rows.getTimestamp(3).toInstant()), RECENT_ACTIVITY);
            List<String> contact = jdbc.queryForList(CONTACT, String.class, tenant);
            return jdbc.queryForObject(TENANT, (rows, row) -> new OrganisationDetail(
                    tenant, organization.name(), organization.legalName(), organization.gstin(),
                    contact.isEmpty() ? null : contact.getFirst(), organization.countryCode(), organization.timezone(),
                    rows.getTimestamp("created_at").toInstant(), instant(rows, "suspended_at"),
                    rows.getString("suspension_reason"), subscriptions.current(), usage, subscriptions.invoices(),
                    subscriptions.payments(), activity), tenant);
        });
    }

    private <T> T inside(UUID tenant, java.util.function.Supplier<T> work) {
        UUID admin = TenantContext.getCurrentUserId();
        return TenantContext.callAs(tenant, admin, () -> tx.execute(status -> work.get()));
    }
}
