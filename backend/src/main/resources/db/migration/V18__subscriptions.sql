-- Plans belong to the platform, not to a tenant, so they carry no tenant_id. Prices are data, never code.
CREATE TABLE subscription_plans (
    id UUID PRIMARY KEY,
    code TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    description TEXT NOT NULL,
    monthly_price NUMERIC(12, 2),
    annual_price NUMERIC(12, 2),
    tax_percent NUMERIC(5, 2) NOT NULL DEFAULT 18,
    max_users INTEGER,
    max_facilities INTEGER,
    features TEXT[] NOT NULL DEFAULT '{}',
    trial_days INTEGER NOT NULL DEFAULT 14,
    grace_days INTEGER NOT NULL DEFAULT 7,
    display_order INTEGER NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_plan_prices CHECK (
        COALESCE(monthly_price, 0) >= 0 AND COALESCE(annual_price, 0) >= 0),
    CONSTRAINT ck_plan_tax CHECK (tax_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_plan_limits CHECK (COALESCE(max_users, 1) > 0 AND COALESCE(max_facilities, 1) > 0),
    CONSTRAINT ck_plan_days CHECK (trial_days >= 0 AND grace_days >= 0)
);
CREATE TRIGGER trg_updated_at BEFORE UPDATE ON subscription_plans FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Starting figures. Annual prices are ten months, and the limits are placeholders until the owner sets them.
INSERT INTO subscription_plans
    (id, code, name, description, monthly_price, annual_price, max_users, max_facilities, features, display_order)
VALUES
    (gen_random_uuid(), 'STARTER', 'Starter', 'Basic inspections and suppliers', 2999, 29990, 5, 1, '{}', 1),
    (gen_random_uuid(), 'GROWTH', 'Growth', 'Inspections, NCR, CAPA and reports', 5999, 59990, 15, 2,
     '{NCR_CAPA,REPORTS}', 2),
    (gen_random_uuid(), 'PROFESSIONAL', 'Professional', 'Advanced workflows and analytics', 9999, 99990, 50, 5,
     '{NCR_CAPA,REPORTS,ADVANCED}', 3),
    (gen_random_uuid(), 'ENTERPRISE', 'Enterprise', 'Multi-facility and advanced features', NULL, NULL, NULL, NULL,
     '{NCR_CAPA,REPORTS,ADVANCED}', 4);

-- Granted by an operator with SQL. The application can read this table and has no way to add to it.
CREATE TABLE platform_admins (
    user_id UUID PRIMARY KEY REFERENCES users (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON platform_admins FROM ${approle};

CREATE TABLE subscriptions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    plan_id UUID NOT NULL REFERENCES subscription_plans (id),
    status TEXT NOT NULL,
    billing_cycle TEXT,
    current_period_ends_at TIMESTAMPTZ NOT NULL,
    cancelled_at TIMESTAMPTZ,
    last_notice_days INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_subscriptions_tenant UNIQUE (tenant_id),
    CONSTRAINT uq_subscriptions_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_subscriptions_status CHECK (status IN ('TRIALING', 'ACTIVE')),
    CONSTRAINT ck_subscriptions_cycle CHECK (billing_cycle IN ('MONTHLY', 'ANNUAL'))
);
SELECT apply_tenant_standards('subscriptions');

-- Everything printed on an invoice is copied here, so that later edits to a plan or an organisation leave it alone.
CREATE TABLE subscription_invoices (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    subscription_id UUID NOT NULL,
    invoice_number TEXT NOT NULL,
    plan_id UUID NOT NULL REFERENCES subscription_plans (id),
    plan_name TEXT NOT NULL,
    billing_cycle TEXT NOT NULL,
    amount NUMERIC(12, 2) NOT NULL,
    tax_percent NUMERIC(5, 2) NOT NULL,
    tax_amount NUMERIC(12, 2) NOT NULL,
    total NUMERIC(12, 2) NOT NULL,
    status TEXT NOT NULL,
    buyer_name TEXT NOT NULL,
    buyer_gstin TEXT,
    buyer_address TEXT,
    issued_on DATE NOT NULL,
    due_on DATE NOT NULL,
    paid_at TIMESTAMPTZ,
    period_starts_at TIMESTAMPTZ,
    period_ends_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_invoices_number UNIQUE (tenant_id, invoice_number),
    CONSTRAINT uq_invoices_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_invoices_subscription FOREIGN KEY (tenant_id, subscription_id)
        REFERENCES subscriptions (tenant_id, id),
    CONSTRAINT ck_invoices_status CHECK (status IN ('ISSUED', 'PAID', 'VOID')),
    CONSTRAINT ck_invoices_cycle CHECK (billing_cycle IN ('MONTHLY', 'ANNUAL')),
    CONSTRAINT ck_invoices_amounts CHECK (amount >= 0 AND tax_amount >= 0 AND total = amount + tax_amount)
);
SELECT apply_tenant_standards('subscription_invoices');
CREATE UNIQUE INDEX uq_invoices_one_open ON subscription_invoices (tenant_id) WHERE status = 'ISSUED';

-- The unique reference makes a repeated gateway callback, or a repeated entry, harmless.
CREATE TABLE payment_transactions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    invoice_id UUID NOT NULL,
    provider TEXT NOT NULL,
    provider_reference TEXT NOT NULL,
    amount NUMERIC(12, 2) NOT NULL,
    status TEXT NOT NULL,
    failure_reason TEXT,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_payments_reference UNIQUE (tenant_id, provider, provider_reference),
    CONSTRAINT fk_payments_invoice FOREIGN KEY (tenant_id, invoice_id)
        REFERENCES subscription_invoices (tenant_id, id),
    CONSTRAINT ck_payments_status CHECK (status IN ('SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_payments_amount CHECK (amount >= 0)
);
SELECT apply_tenant_standards('payment_transactions');
CREATE INDEX ix_payments_invoice ON payment_transactions (tenant_id, invoice_id);

-- Organisations that exist today start a trial of the same length as a new sign-up. Row-level security binds this
-- schema's owner too, so it steps aside here, or they would get no subscription and turn read-only.
ALTER TABLE tenants NO FORCE ROW LEVEL SECURITY;
ALTER TABLE subscriptions NO FORCE ROW LEVEL SECURITY;
INSERT INTO subscriptions (id, tenant_id, plan_id, status, current_period_ends_at)
SELECT gen_random_uuid(), t.id, p.id, 'TRIALING', now() + make_interval(days => p.trial_days)
FROM tenants t CROSS JOIN subscription_plans p
WHERE p.code = 'PROFESSIONAL';
ALTER TABLE subscriptions FORCE ROW LEVEL SECURITY;
ALTER TABLE tenants FORCE ROW LEVEL SECURITY;

DO $$
BEGIN
    EXECUTE format('CREATE POLICY subscription_scan ON subscriptions FOR SELECT TO %I USING (true)', current_user);
END;
$$;

-- Ids only, for the expiry job.
CREATE FUNCTION tenants_with_subscriptions_ending() RETURNS SETOF UUID
    LANGUAGE sql SECURITY DEFINER SET search_path = public AS
$$
    SELECT s.tenant_id FROM subscriptions s
    JOIN subscription_plans p ON p.id = s.plan_id
    WHERE s.current_period_ends_at < now() + INTERVAL '7 days'
      AND COALESCE(s.last_notice_days, 8) > -p.grace_days;
$$;
REVOKE ALL ON FUNCTION tenants_with_subscriptions_ending() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenants_with_subscriptions_ending() TO ${approle};

-- Ids only. The platform console then reads each organisation under that organisation's own tenant context.
CREATE FUNCTION platform_tenant_ids() RETURNS SETOF UUID
    LANGUAGE sql SECURITY DEFINER SET search_path = public AS
$$
    SELECT tenant_id FROM subscriptions ORDER BY current_period_ends_at;
$$;
REVOKE ALL ON FUNCTION platform_tenant_ids() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_tenant_ids() TO ${approle};
