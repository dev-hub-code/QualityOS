-- The Control Center, first part: plan history, and the reads that cross organisations.

ALTER TABLE subscription_plans
    ADD COLUMN currency TEXT NOT NULL DEFAULT 'INR',
    ADD COLUMN public_visible BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN archived_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_plan_currency CHECK (currency = 'INR'),
    ADD CONSTRAINT ck_plan_archived CHECK (archived_at IS NULL OR NOT active);

-- Each saved state of a plan. Nothing may change or remove one.
CREATE TABLE subscription_plan_versions (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES subscription_plans (id),
    version BIGINT NOT NULL,
    snapshot JSONB NOT NULL,
    reason TEXT,
    changed_by UUID,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_plan_versions UNIQUE (plan_id, version)
);
REVOKE UPDATE, DELETE, TRUNCATE ON subscription_plan_versions FROM ${approle};

INSERT INTO subscription_plan_versions (id, plan_id, version, snapshot, reason)
SELECT gen_random_uuid(), p.id, p.version,
       jsonb_build_object('name', p.name, 'description', p.description, 'monthlyPrice', p.monthly_price,
                          'annualPrice', p.annual_price, 'taxPercent', p.tax_percent, 'maxUsers', p.max_users,
                          'maxFacilities', p.max_facilities, 'features', p.features, 'trialDays', p.trial_days,
                          'graceDays', p.grace_days, 'active', p.active, 'publicVisible', TRUE),
       'State when history began'
FROM subscription_plans p;

DO $$
BEGIN
    EXECUTE format('CREATE POLICY platform_scan ON payment_transactions FOR SELECT TO %I USING (true)', current_user);
END;
$$;

-- The functions below answer for every organisation, so each one refuses anyone who is not an administrator,
-- whatever the application above it has checked.
CREATE FUNCTION require_platform_admin() RETURNS void
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM platform_admins WHERE user_id = current_app_user_id()) THEN
        RAISE EXCEPTION 'platform administrator required' USING ERRCODE = 'insufficient_privilege';
    END IF;
END;
$$;
REVOKE ALL ON FUNCTION require_platform_admin() FROM PUBLIC;

CREATE FUNCTION platform_state(p_status TEXT, p_ends TIMESTAMPTZ, p_grace INT, p_at TIMESTAMPTZ) RETURNS TEXT
    LANGUAGE sql IMMUTABLE AS
$$
    SELECT CASE
        WHEN p_at < p_ends THEN CASE p_status WHEN 'TRIALING' THEN 'TRIAL' ELSE 'ACTIVE' END
        WHEN p_at < p_ends + make_interval(days => p_grace) THEN 'GRACE'
        ELSE 'READ_ONLY'
    END;
$$;

CREATE FUNCTION platform_organisation_page(
    p_search TEXT, p_plan TEXT, p_state TEXT, p_unpaid BOOLEAN, p_from TIMESTAMPTZ, p_to TIMESTAMPTZ,
    p_sort TEXT, p_limit INT, p_offset INT)
    RETURNS TABLE (tenant_id UUID, name TEXT, gstin TEXT, contact_email TEXT, plan_name TEXT, state TEXT,
                   ends_at TIMESTAMPTZ, registered_at TIMESTAMPTZ, open_invoices BIGINT, total BIGINT)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS
$$
#variable_conflict use_column
DECLARE
    pattern TEXT := '%' || replace(replace(replace(COALESCE(p_search, ''), '\', '\\'), '%', '\%'), '_', '\_') || '%';
BEGIN
    PERFORM require_platform_admin();
    RETURN QUERY
    WITH listed AS (
        SELECT t.id, t.name, t.gstin, t.created_at, s.current_period_ends_at, p.name AS plan_name,
               platform_state(s.status, s.current_period_ends_at, p.grace_days, now()) AS state,
               (SELECT u.email FROM tenant_memberships m JOIN users u ON u.id = m.user_id
                WHERE m.tenant_id = t.id ORDER BY m.created_at LIMIT 1) AS contact_email,
               (SELECT count(*) FROM subscription_invoices i
                WHERE i.tenant_id = t.id AND i.status = 'ISSUED') AS open_invoices
        FROM subscriptions s
        JOIN tenants t ON t.id = s.tenant_id
        JOIN subscription_plans p ON p.id = s.plan_id
        WHERE (p_plan IS NULL OR p.code = p_plan)
          AND (p_from IS NULL OR t.created_at >= p_from)
          AND (p_to IS NULL OR t.created_at < p_to)
          AND (p_search IS NULL OR t.name ILIKE pattern OR t.gstin ILIKE pattern
               OR EXISTS (SELECT 1 FROM tenant_memberships m JOIN users u ON u.id = m.user_id
                          WHERE m.tenant_id = t.id AND u.email ILIKE pattern))
    ), chosen AS (
        SELECT * FROM listed l
        WHERE (p_state IS NULL OR l.state = p_state)
          AND (p_unpaid IS NOT TRUE OR l.open_invoices > 0)
    )
    SELECT c.id, c.name, c.gstin, c.contact_email, c.plan_name, c.state, c.current_period_ends_at, c.created_at,
           c.open_invoices, count(*) OVER ()
    FROM chosen c
    ORDER BY CASE WHEN p_sort = 'NAME' THEN c.name END,
             CASE WHEN p_sort = 'NEWEST' THEN c.created_at END DESC,
             CASE WHEN p_sort = 'ENDING' THEN c.current_period_ends_at END,
             (c.open_invoices > 0) DESC, c.current_period_ends_at, c.id
    LIMIT p_limit OFFSET p_offset;
END;
$$;
REVOKE ALL ON FUNCTION platform_organisation_page(TEXT, TEXT, TEXT, BOOLEAN, TIMESTAMPTZ, TIMESTAMPTZ, TEXT, INT, INT)
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_organisation_page(TEXT, TEXT, TEXT, BOOLEAN, TIMESTAMPTZ, TIMESTAMPTZ, TEXT, INT, INT)
    TO ${approle};

CREATE FUNCTION platform_subscriber_count(p_plan UUID) RETURNS BIGINT
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS
$$
BEGIN
    PERFORM require_platform_admin();
    RETURN (SELECT count(*) FROM subscriptions WHERE plan_id = p_plan);
END;
$$;
REVOKE ALL ON FUNCTION platform_subscriber_count(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_subscriber_count(UUID) TO ${approle};

-- What was under contract at a moment: the latest paid invoice covering it, for each organisation, without tax and
-- with an annual invoice spread over twelve months. docs/PLATFORM_ADMIN.md defines every figure built on this.
CREATE FUNCTION platform_contracts_at(p_at TIMESTAMPTZ)
    RETURNS TABLE (tenant_id UUID, plan_name TEXT, monthly NUMERIC)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS
$$
    SELECT DISTINCT ON (i.tenant_id) i.tenant_id, i.plan_name,
           CASE i.billing_cycle WHEN 'ANNUAL' THEN i.amount / 12 ELSE i.amount END
    FROM subscription_invoices i
    JOIN subscriptions s ON s.tenant_id = i.tenant_id
    WHERE i.status = 'PAID' AND i.period_starts_at <= p_at AND i.period_ends_at > p_at
      AND (s.cancelled_at IS NULL OR s.cancelled_at > p_at)
    ORDER BY i.tenant_id, i.paid_at DESC;
$$;
REVOKE ALL ON FUNCTION platform_contracts_at(TIMESTAMPTZ) FROM PUBLIC;

CREATE FUNCTION platform_dashboard(p_start TIMESTAMPTZ, p_end TIMESTAMPTZ, p_zone TEXT) RETURNS JSONB
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS
$$
DECLARE
    states JSONB;
    mrr NUMERIC;
    paying_at_start BIGINT;
    cancelled BIGINT;
    result JSONB;
BEGIN
    PERFORM require_platform_admin();
    SELECT jsonb_object_agg(x.state, x.n) INTO states FROM (
        SELECT platform_state(s.status, s.current_period_ends_at, p.grace_days, now()) AS state, count(*) AS n
        FROM subscriptions s JOIN subscription_plans p ON p.id = s.plan_id GROUP BY 1) x;
    SELECT COALESCE(sum(c.monthly), 0) INTO mrr FROM platform_contracts_at(now()) c;
    SELECT count(*) INTO paying_at_start FROM platform_contracts_at(p_start);
    SELECT count(*) INTO cancelled FROM subscriptions WHERE cancelled_at >= p_start AND cancelled_at < p_end;

    SELECT jsonb_build_object(
        'organisations', (SELECT count(*) FROM subscriptions),
        'active', COALESCE((states ->> 'ACTIVE')::BIGINT, 0),
        'trials', COALESCE((states ->> 'TRIAL')::BIGINT, 0),
        'grace', COALESCE((states ->> 'GRACE')::BIGINT, 0),
        'readOnly', COALESCE((states ->> 'READ_ONLY')::BIGINT, 0),
        'expired', COALESCE((states ->> 'GRACE')::BIGINT, 0) + COALESCE((states ->> 'READ_ONLY')::BIGINT, 0),
        'mrr', round(mrr, 2),
        'arr', round(mrr * 12, 2),
        'outstanding', (SELECT COALESCE(sum(total), 0) FROM subscription_invoices WHERE status = 'ISSUED'),
        'outstandingInvoices', (SELECT count(*) FROM subscription_invoices WHERE status = 'ISSUED'),
        'collected', (SELECT COALESCE(sum(amount), 0) FROM payment_transactions
                      WHERE status = 'SUCCEEDED' AND occurred_at >= p_start AND occurred_at < p_end),
        'collectionRate', (SELECT round(100 * sum(total) FILTER (WHERE status = 'PAID') / NULLIF(sum(total), 0), 1)
                           FROM subscription_invoices
                           WHERE status <> 'VOID'
                             AND issued_on >= (p_start AT TIME ZONE p_zone)::DATE
                             AND issued_on < (p_end AT TIME ZONE p_zone)::DATE),
        'churnRate', round(100.0 * cancelled / NULLIF(paying_at_start, 0), 1),
        'plans', (SELECT COALESCE(jsonb_agg(jsonb_build_object('name', x.name, 'count', x.n) ORDER BY x.n DESC), '[]')
                  FROM (SELECT p.name, count(*) AS n FROM subscriptions s
                        JOIN subscription_plans p ON p.id = s.plan_id GROUP BY p.name) x),
        'months', (SELECT jsonb_agg(m.row ORDER BY m.month) FROM (
            SELECT b.month, jsonb_build_object(
                'month', to_char(b.month, 'YYYY-MM'),
                'mrr', (SELECT COALESCE(round(sum(c.monthly), 2), 0) FROM platform_contracts_at(b.point) c),
                'paying', (SELECT count(*) FROM platform_contracts_at(b.point)),
                'registered', (SELECT count(*) FROM tenants t WHERE t.created_at >= b.opens AND t.created_at < b.closes),
                'converted', (SELECT count(*) FROM tenants t
                              WHERE t.created_at >= b.opens AND t.created_at < b.closes
                                AND EXISTS (SELECT 1 FROM subscription_invoices i
                                            WHERE i.tenant_id = t.id AND i.status = 'PAID')),
                'collected', (SELECT COALESCE(sum(amount), 0) FROM payment_transactions
                              WHERE status = 'SUCCEEDED' AND occurred_at >= b.opens AND occurred_at < b.closes),
                'cancelled', (SELECT count(*) FROM subscriptions
                              WHERE cancelled_at >= b.opens AND cancelled_at < b.closes)) AS row
            FROM (SELECT g.month,
                         g.month AT TIME ZONE p_zone AS opens,
                         (g.month + INTERVAL '1 month') AT TIME ZONE p_zone AS closes,
                         LEAST((g.month + INTERVAL '1 month') AT TIME ZONE p_zone, now()) AS point
                  FROM generate_series(date_trunc('month', p_start AT TIME ZONE p_zone),
                                       (p_end - INTERVAL '1 microsecond') AT TIME ZONE p_zone,
                                       INTERVAL '1 month') AS g (month)) b) m)
    ) INTO result;
    RETURN result;
END;
$$;
REVOKE ALL ON FUNCTION platform_dashboard(TIMESTAMPTZ, TIMESTAMPTZ, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_dashboard(TIMESTAMPTZ, TIMESTAMPTZ, TEXT) TO ${approle};
