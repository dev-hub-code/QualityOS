-- Plans, subscriptions, invoices and payments.
-- ${approle} is the unprivileged role the application connects as.

SET LOCAL check_function_bodies = off;

CREATE FUNCTION platform_invoice_page(p_search text, p_status text, p_overdue boolean, p_from date, p_to date, p_min numeric, p_max numeric, p_limit integer, p_offset integer) RETURNS TABLE(id uuid, tenant_id uuid, organisation text, invoice_number text, plan_name text, billing_cycle text, period_starts_at timestamp with time zone, period_ends_at timestamp with time zone, amount numeric, tax_amount numeric, invoice_total numeric, paid numeric, outstanding numeric, status text, issued_on date, due_on date, total bigint)
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
#variable_conflict use_column
DECLARE
    pattern TEXT := '%' || replace(replace(replace(COALESCE(p_search, ''), '\', '\\'), '%', '\%'), '_', '\_') || '%';
BEGIN
    PERFORM require_platform_admin();
    RETURN QUERY
    WITH received AS (
        SELECT p.invoice_id, sum(p.amount) AS paid FROM payment_transactions p
        WHERE p.status = 'SUCCEEDED' GROUP BY p.invoice_id
    )
    SELECT i.id, i.tenant_id, t.name, i.invoice_number, i.plan_name, i.billing_cycle, i.period_starts_at,
           i.period_ends_at, i.amount, i.tax_amount, i.total, COALESCE(r.paid, 0),
           CASE i.status WHEN 'ISSUED' THEN i.total - COALESCE(r.paid, 0) ELSE 0 END,
           i.status, i.issued_on, i.due_on, count(*) OVER ()
    FROM subscription_invoices i
    JOIN tenants t ON t.id = i.tenant_id
    LEFT JOIN received r ON r.invoice_id = i.id
    WHERE (p_search IS NULL OR i.invoice_number ILIKE pattern OR t.name ILIKE pattern)
      AND (p_status IS NULL OR i.status = p_status)
      AND (p_overdue IS NOT TRUE OR (i.status = 'ISSUED' AND i.due_on < current_date))
      AND (p_from IS NULL OR i.issued_on >= p_from)
      AND (p_to IS NULL OR i.issued_on <= p_to)
      AND (p_min IS NULL OR i.total >= p_min)
      AND (p_max IS NULL OR i.total <= p_max)
    ORDER BY i.issued_on DESC, i.id DESC
    LIMIT p_limit OFFSET p_offset;
END;
$$;

CREATE FUNCTION platform_payment_page(p_search text, p_status text, p_from date, p_to date, p_limit integer, p_offset integer) RETURNS TABLE(id uuid, tenant_id uuid, organisation text, invoice_id uuid, invoice_number text, provider text, reference text, method text, amount numeric, status text, failure_reason text, paid_on date, notes text, recorded_by text, recorded_at timestamp with time zone, total bigint)
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
#variable_conflict use_column
DECLARE
    pattern TEXT := '%' || replace(replace(replace(COALESCE(p_search, ''), '\', '\\'), '%', '\%'), '_', '\_') || '%';
BEGIN
    PERFORM require_platform_admin();
    RETURN QUERY
    SELECT p.id, p.tenant_id, t.name, p.invoice_id, i.invoice_number, p.provider, p.provider_reference, p.method,
           p.amount, p.status, p.failure_reason, p.paid_on, p.notes, u.email, p.occurred_at, count(*) OVER ()
    FROM payment_transactions p
    JOIN tenants t ON t.id = p.tenant_id
    JOIN subscription_invoices i ON i.id = p.invoice_id
    LEFT JOIN users u ON u.id = p.created_by
    WHERE (p_search IS NULL OR p.provider_reference ILIKE pattern OR i.invoice_number ILIKE pattern
           OR t.name ILIKE pattern)
      AND (p_status IS NULL OR p.status = p_status)
      AND (p_from IS NULL OR COALESCE(p.paid_on, (p.occurred_at AT TIME ZONE 'Asia/Kolkata')::DATE) >= p_from)
      AND (p_to IS NULL OR COALESCE(p.paid_on, (p.occurred_at AT TIME ZONE 'Asia/Kolkata')::DATE) <= p_to)
    ORDER BY p.occurred_at DESC, p.id DESC
    LIMIT p_limit OFFSET p_offset;
END;
$$;

-- A bank reference pays one invoice, anywhere on the platform and however it was typed. Row-level security would
-- only show this organisation's payments. Repeating it on the same invoice is a retry, which settling makes harmless.
CREATE FUNCTION platform_reference_used_elsewhere(p_reference text, p_invoice uuid) RETURNS boolean
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
BEGIN
    PERFORM require_platform_admin();
    RETURN EXISTS (SELECT 1 FROM payment_transactions
                   WHERE upper(provider_reference) = upper(p_reference) AND invoice_id <> p_invoice);
END;
$$;

-- Ids only. The platform console then reads each organisation under that organisation's own tenant context.
CREATE FUNCTION platform_tenant_ids() RETURNS SETOF uuid
    LANGUAGE sql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
    SELECT s.tenant_id FROM subscriptions s
    ORDER BY EXISTS (SELECT 1 FROM subscription_invoices i WHERE i.tenant_id = s.tenant_id AND i.status = 'ISSUED') DESC,
             s.current_period_ends_at;
$$;

CREATE FUNCTION platform_trial_summary(p_zone text) RETURNS jsonb
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
DECLARE
    month_start TIMESTAMPTZ := date_trunc('month', now() AT TIME ZONE p_zone) AT TIME ZONE p_zone;
    result JSONB;
BEGIN
    PERFORM require_platform_admin();
    WITH first_paid AS (
        SELECT tenant_id, min(paid_at) AS paid_at FROM subscription_invoices WHERE status = 'PAID' GROUP BY tenant_id
    )
    SELECT jsonb_build_object(
        'endingIn3Days', (SELECT count(*) FROM subscriptions WHERE status = 'TRIALING'
                          AND current_period_ends_at >= now() AND current_period_ends_at < now() + INTERVAL '3 days'),
        'endingIn7Days', (SELECT count(*) FROM subscriptions WHERE status = 'TRIALING'
                          AND current_period_ends_at >= now() AND current_period_ends_at < now() + INTERVAL '7 days'),
        'convertedThisMonth', (SELECT count(*) FROM first_paid WHERE paid_at >= month_start),
        'conversionRate', (SELECT round(100.0 * (SELECT count(*) FROM first_paid) / NULLIF(count(*), 0), 1)
                           FROM subscriptions))
    INTO result;
    RETURN result;
END;
$$;

-- Ids only, for the notice job: reminders about the subscription, and invoices past due that have not been chased.
CREATE FUNCTION tenants_with_subscriptions_ending() RETURNS SETOF uuid
    LANGUAGE sql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
    SELECT s.tenant_id FROM subscriptions s
    JOIN subscription_plans p ON p.id = s.plan_id
    WHERE ((s.current_period_ends_at < now() + INTERVAL '7 days'
            AND COALESCE(s.last_notice_days, 8) > -p.grace_days)
           OR EXISTS (SELECT 1 FROM subscription_invoices i
                      WHERE i.tenant_id = s.tenant_id AND i.status = 'ISSUED'
                        AND i.overdue_notified_at IS NULL AND i.due_on < current_date));
$$;

-- The unique reference makes a repeated gateway callback, or a repeated entry, harmless.
CREATE TABLE payment_transactions (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    invoice_id uuid NOT NULL,
    provider text NOT NULL,
    provider_reference text NOT NULL,
    amount numeric(12,2) NOT NULL,
    status text NOT NULL,
    failure_reason text,
    occurred_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    method text,
    paid_on date,
    notes text,
    CONSTRAINT ck_payments_amount CHECK ((amount >= (0)::numeric)),
    CONSTRAINT ck_payments_method CHECK ((method = ANY (ARRAY['BANK_TRANSFER'::text, 'UPI'::text, 'NEFT'::text, 'RTGS'::text, 'IMPS'::text, 'CHEQUE'::text, 'OTHER'::text]))),
    CONSTRAINT ck_payments_status CHECK ((status = ANY (ARRAY['SUCCEEDED'::text, 'FAILED'::text])))
);

ALTER TABLE ONLY payment_transactions FORCE ROW LEVEL SECURITY;

-- Granted by an operator with SQL. The application can read this table and has no way to add to it.
CREATE TABLE platform_admins (
    user_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    role text DEFAULT 'SUPER_ADMIN'::text NOT NULL,
    deactivated_at timestamp with time zone,
    added_by uuid,
    reauthenticated_until timestamp with time zone,
    CONSTRAINT ck_platform_admin_role CHECK ((role = ANY (ARRAY['SUPER_ADMIN'::text, 'BILLING_ADMIN'::text, 'SUPPORT_ADMIN'::text, 'READ_ONLY_AUDITOR'::text])))
);

-- Everything printed on an invoice is copied here, so that later edits to a plan or an organisation leave it alone.
CREATE TABLE subscription_invoices (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    subscription_id uuid NOT NULL,
    invoice_number text NOT NULL,
    plan_id uuid NOT NULL,
    plan_name text NOT NULL,
    billing_cycle text NOT NULL,
    amount numeric(12,2) NOT NULL,
    tax_percent numeric(5,2) NOT NULL,
    tax_amount numeric(12,2) NOT NULL,
    total numeric(12,2) NOT NULL,
    status text NOT NULL,
    buyer_name text NOT NULL,
    buyer_gstin text,
    buyer_address text,
    issued_on date NOT NULL,
    due_on date NOT NULL,
    paid_at timestamp with time zone,
    period_starts_at timestamp with time zone,
    period_ends_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    tax_treatment text,
    place_of_supply text,
    cgst_amount numeric(12,2),
    sgst_amount numeric(12,2),
    igst_amount numeric(12,2),
    sac_code text,
    seller_gstin text,
    overdue_notified_at timestamp with time zone,
    CONSTRAINT ck_invoices_amounts CHECK (((amount >= (0)::numeric) AND (tax_amount >= (0)::numeric) AND (total = (amount + tax_amount)))),
    CONSTRAINT ck_invoices_cycle CHECK ((billing_cycle = ANY (ARRAY['MONTHLY'::text, 'ANNUAL'::text]))),
    CONSTRAINT ck_invoices_status CHECK ((status = ANY (ARRAY['ISSUED'::text, 'PAID'::text, 'VOID'::text]))),
    CONSTRAINT ck_invoices_tax_treatment CHECK ((tax_treatment = ANY (ARRAY['INTRA_STATE'::text, 'INTER_STATE'::text, 'UNDETERMINED'::text])))
);

ALTER TABLE ONLY subscription_invoices FORCE ROW LEVEL SECURITY;

-- Plans belong to the platform, not to a tenant, so they carry no tenant_id. Prices are data, never code.
CREATE TABLE subscription_plans (
    id uuid NOT NULL,
    code text NOT NULL,
    name text NOT NULL,
    description text NOT NULL,
    monthly_price numeric(12,2),
    annual_price numeric(12,2),
    tax_percent numeric(5,2) DEFAULT 18 NOT NULL,
    max_users integer,
    max_facilities integer,
    features text[] DEFAULT '{}'::text[] NOT NULL,
    trial_days integer DEFAULT 14 NOT NULL,
    grace_days integer DEFAULT 7 NOT NULL,
    display_order integer DEFAULT 0 NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    currency text DEFAULT 'INR'::text NOT NULL,
    public_visible boolean DEFAULT true NOT NULL,
    archived_at timestamp with time zone,
    max_storage_mb integer,
    max_monthly_inspections integer,
    max_attachments integer,
    feature_values jsonb DEFAULT '{}'::jsonb NOT NULL,
    CONSTRAINT ck_plan_archived CHECK (((archived_at IS NULL) OR (NOT active))),
    CONSTRAINT ck_plan_currency CHECK ((currency = 'INR'::text)),
    CONSTRAINT ck_plan_days CHECK (((trial_days >= 0) AND (grace_days >= 0))),
    CONSTRAINT ck_plan_limits CHECK (((COALESCE(max_users, 1) > 0) AND (COALESCE(max_facilities, 1) > 0))),
    CONSTRAINT ck_plan_prices CHECK (((COALESCE(monthly_price, (0)::numeric) >= (0)::numeric) AND (COALESCE(annual_price, (0)::numeric) >= (0)::numeric))),
    CONSTRAINT ck_plan_tax CHECK (((tax_percent >= (0)::numeric) AND (tax_percent <= (100)::numeric))),
    CONSTRAINT ck_plan_usage_limits CHECK (((COALESCE(max_storage_mb, 1) > 0) AND (COALESCE(max_monthly_inspections, 1) > 0) AND (COALESCE(max_attachments, 1) > 0)))
);

CREATE TABLE subscriptions (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    plan_id uuid NOT NULL,
    status text NOT NULL,
    billing_cycle text,
    current_period_ends_at timestamp with time zone NOT NULL,
    cancelled_at timestamp with time zone,
    last_notice_days integer,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_subscriptions_cycle CHECK ((billing_cycle = ANY (ARRAY['MONTHLY'::text, 'ANNUAL'::text]))),
    CONSTRAINT ck_subscriptions_status CHECK ((status = ANY (ARRAY['TRIALING'::text, 'ACTIVE'::text])))
);

ALTER TABLE ONLY subscriptions FORCE ROW LEVEL SECURITY;

-- Starting rows, loaded before row-level security is switched on.
INSERT INTO subscription_plans VALUES ('dce65d14-bd62-47a9-aa36-be51777f4173', 'STARTER', 'Starter', 'Basic inspections and suppliers', 2999.00, 29990.00, 18.00, 5, 1, '{}', 14, 7, 1, true, '2026-10-31 22:37:42.332607+00', '2026-10-31 22:37:42.332607+00', NULL, NULL, 0, 'INR', true, NULL, NULL, NULL, NULL, '{}');
INSERT INTO subscription_plans VALUES ('95a07e4f-55c4-4d5b-983e-a1087cc99040', 'GROWTH', 'Growth', 'Inspections, NCR, CAPA and reports', 5999.00, 59990.00, 18.00, 15, 2, '{NCR_CAPA,REPORTS}', 14, 7, 2, true, '2026-10-31 22:37:42.332607+00', '2026-10-31 22:37:42.332607+00', NULL, NULL, 0, 'INR', true, NULL, NULL, NULL, NULL, '{}');
INSERT INTO subscription_plans VALUES ('0c72cfc7-354f-4cbe-9c80-dc26bb6e1a5a', 'PROFESSIONAL', 'Professional', 'Advanced workflows and analytics', 9999.00, 99990.00, 18.00, 50, 5, '{NCR_CAPA,REPORTS,ADVANCED}', 14, 7, 3, true, '2026-10-31 22:37:42.332607+00', '2026-10-31 22:37:42.332607+00', NULL, NULL, 0, 'INR', true, NULL, NULL, NULL, NULL, '{}');
INSERT INTO subscription_plans VALUES ('52185dbf-7654-4a33-b5fd-a2cbd088b482', 'ENTERPRISE', 'Enterprise', 'Multi-facility and advanced features', NULL, NULL, 18.00, NULL, NULL, '{NCR_CAPA,REPORTS,ADVANCED}', 14, 7, 4, true, '2026-10-31 22:37:42.332607+00', '2026-10-31 22:37:42.332607+00', NULL, NULL, 0, 'INR', true, NULL, NULL, NULL, NULL, '{}');

ALTER TABLE ONLY payment_transactions
    ADD CONSTRAINT payment_transactions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY platform_admins
    ADD CONSTRAINT platform_admins_pkey PRIMARY KEY (user_id);

ALTER TABLE ONLY subscription_invoices
    ADD CONSTRAINT subscription_invoices_pkey PRIMARY KEY (id);

ALTER TABLE ONLY subscription_plans
    ADD CONSTRAINT subscription_plans_code_key UNIQUE (code);

ALTER TABLE ONLY subscription_plans
    ADD CONSTRAINT subscription_plans_pkey PRIMARY KEY (id);

ALTER TABLE ONLY subscriptions
    ADD CONSTRAINT subscriptions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY subscription_invoices
    ADD CONSTRAINT uq_invoices_number UNIQUE (tenant_id, invoice_number);

ALTER TABLE ONLY subscription_invoices
    ADD CONSTRAINT uq_invoices_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY payment_transactions
    ADD CONSTRAINT uq_payments_reference UNIQUE (tenant_id, provider, provider_reference);

ALTER TABLE ONLY subscriptions
    ADD CONSTRAINT uq_subscriptions_tenant UNIQUE (tenant_id);

ALTER TABLE ONLY subscriptions
    ADD CONSTRAINT uq_subscriptions_tenant_id UNIQUE (tenant_id, id);

CREATE INDEX ix_payments_invoice ON payment_transactions USING btree (tenant_id, invoice_id);

CREATE INDEX ix_payments_reference ON payment_transactions USING btree (upper(provider_reference));

CREATE UNIQUE INDEX uq_invoices_one_open ON subscription_invoices USING btree (tenant_id) WHERE (status = 'ISSUED'::text);

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON payment_transactions FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON subscription_invoices FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON subscription_plans FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON subscriptions FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE ONLY subscription_invoices
    ADD CONSTRAINT fk_invoices_subscription FOREIGN KEY (tenant_id, subscription_id) REFERENCES subscriptions(tenant_id, id);

ALTER TABLE ONLY payment_transactions
    ADD CONSTRAINT fk_payments_invoice FOREIGN KEY (tenant_id, invoice_id) REFERENCES subscription_invoices(tenant_id, id);

ALTER TABLE ONLY payment_transactions
    ADD CONSTRAINT payment_transactions_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY platform_admins
    ADD CONSTRAINT platform_admins_added_by_fkey FOREIGN KEY (added_by) REFERENCES users(id);

ALTER TABLE ONLY platform_admins
    ADD CONSTRAINT platform_admins_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id);

ALTER TABLE ONLY subscription_invoices
    ADD CONSTRAINT subscription_invoices_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES subscription_plans(id);

ALTER TABLE ONLY subscription_invoices
    ADD CONSTRAINT subscription_invoices_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY subscriptions
    ADD CONSTRAINT subscriptions_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES subscription_plans(id);

ALTER TABLE ONLY subscriptions
    ADD CONSTRAINT subscriptions_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

CREATE POLICY invoice_scan ON subscription_invoices FOR SELECT TO "${flyway:user}" USING (true);

ALTER TABLE payment_transactions ENABLE ROW LEVEL SECURITY;

CREATE POLICY platform_scan ON payment_transactions FOR SELECT TO "${flyway:user}" USING (true);

ALTER TABLE subscription_invoices ENABLE ROW LEVEL SECURITY;

CREATE POLICY subscription_scan ON subscriptions FOR SELECT TO "${flyway:user}" USING (true);

ALTER TABLE subscriptions ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON payment_transactions USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON subscription_invoices USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON subscriptions USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

REVOKE ALL ON FUNCTION platform_invoice_page(p_search text, p_status text, p_overdue boolean, p_from date, p_to date, p_min numeric, p_max numeric, p_limit integer, p_offset integer) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_invoice_page(p_search text, p_status text, p_overdue boolean, p_from date, p_to date, p_min numeric, p_max numeric, p_limit integer, p_offset integer) TO ${approle};

REVOKE ALL ON FUNCTION platform_payment_page(p_search text, p_status text, p_from date, p_to date, p_limit integer, p_offset integer) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_payment_page(p_search text, p_status text, p_from date, p_to date, p_limit integer, p_offset integer) TO ${approle};

REVOKE ALL ON FUNCTION platform_reference_used_elsewhere(p_reference text, p_invoice uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_reference_used_elsewhere(p_reference text, p_invoice uuid) TO ${approle};

REVOKE ALL ON FUNCTION platform_tenant_ids() FROM PUBLIC;
GRANT ALL ON FUNCTION platform_tenant_ids() TO ${approle};

REVOKE ALL ON FUNCTION platform_trial_summary(p_zone text) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_trial_summary(p_zone text) TO ${approle};

REVOKE ALL ON FUNCTION tenants_with_subscriptions_ending() FROM PUBLIC;
GRANT ALL ON FUNCTION tenants_with_subscriptions_ending() TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE payment_transactions TO ${approle};

GRANT SELECT ON TABLE platform_admins TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE subscription_invoices TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE subscription_plans TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE subscriptions TO ${approle};
