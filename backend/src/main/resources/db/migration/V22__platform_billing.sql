-- The Control Center, second part: suspension, GST detail on invoices, and how a payment was made.

-- A sign-up that expired is also SUSPENDED, but only an operator's suspension sets these, and only that can be undone.
ALTER TABLE tenants
    ADD COLUMN suspended_at TIMESTAMPTZ,
    ADD COLUMN suspension_reason TEXT;

-- Empty on invoices issued before this. Their single GST line stays as it was issued.
ALTER TABLE subscription_invoices
    ADD COLUMN tax_treatment TEXT,
    ADD COLUMN place_of_supply TEXT,
    ADD COLUMN cgst_amount NUMERIC(12, 2),
    ADD COLUMN sgst_amount NUMERIC(12, 2),
    ADD COLUMN igst_amount NUMERIC(12, 2),
    ADD COLUMN sac_code TEXT,
    ADD COLUMN seller_gstin TEXT,
    ADD CONSTRAINT ck_invoices_tax_treatment CHECK (tax_treatment IN ('INTRA_STATE', 'INTER_STATE', 'UNDETERMINED'));

ALTER TABLE payment_transactions
    ADD COLUMN method TEXT,
    ADD COLUMN paid_on DATE,
    ADD COLUMN notes TEXT,
    ADD CONSTRAINT ck_payments_method
        CHECK (method IN ('BANK_TRANSFER', 'UPI', 'NEFT', 'RTGS', 'IMPS', 'CHEQUE', 'OTHER'));
CREATE INDEX ix_payments_reference ON payment_transactions (upper(provider_reference));

DROP FUNCTION platform_organisation_page(TEXT, TEXT, TEXT, BOOLEAN, TIMESTAMPTZ, TIMESTAMPTZ, TEXT, INT, INT);

CREATE FUNCTION platform_organisation_page(
    p_search TEXT, p_plan TEXT, p_state TEXT, p_unpaid BOOLEAN, p_from TIMESTAMPTZ, p_to TIMESTAMPTZ,
    p_sort TEXT, p_limit INT, p_offset INT)
    RETURNS TABLE (tenant_id UUID, subscription_id UUID, name TEXT, gstin TEXT, contact_email TEXT, plan_code TEXT, plan_name TEXT, state TEXT,
                   billing_cycle TEXT, ends_at TIMESTAMPTZ, cancelled_at TIMESTAMPTZ, suspended_at TIMESTAMPTZ,
                   registered_at TIMESTAMPTZ, open_invoices BIGINT, next_due DATE, total BIGINT)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS
$$
#variable_conflict use_column
DECLARE
    pattern TEXT := '%' || replace(replace(replace(COALESCE(p_search, ''), '\', '\\'), '%', '\%'), '_', '\_') || '%';
BEGIN
    PERFORM require_platform_admin();
    RETURN QUERY
    WITH listed AS (
        SELECT t.id, s.id AS subscription_id, t.name, t.gstin, t.created_at, t.suspended_at, s.current_period_ends_at, s.billing_cycle,
               s.cancelled_at, p.code AS plan_code, p.name AS plan_name,
               platform_state(s.status, s.current_period_ends_at, p.grace_days, now()) AS state,
               (SELECT u.email FROM tenant_memberships m JOIN users u ON u.id = m.user_id
                WHERE m.tenant_id = t.id ORDER BY m.created_at LIMIT 1) AS contact_email,
               (SELECT count(*) FROM subscription_invoices i
                WHERE i.tenant_id = t.id AND i.status = 'ISSUED') AS open_invoices,
               (SELECT min(i.due_on) FROM subscription_invoices i
                WHERE i.tenant_id = t.id AND i.status = 'ISSUED') AS next_due
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
        WHERE (p_state IS NULL OR l.state = p_state OR (p_state = 'SUSPENDED' AND l.suspended_at IS NOT NULL)
               OR (p_state = 'CANCELLED' AND l.cancelled_at IS NOT NULL))
          AND (p_unpaid IS NOT TRUE OR l.open_invoices > 0)
    )
    SELECT c.id, c.subscription_id, c.name, c.gstin, c.contact_email, c.plan_code, c.plan_name, c.state, c.billing_cycle,
           c.current_period_ends_at, c.cancelled_at, c.suspended_at, c.created_at, c.open_invoices, c.next_due,
           count(*) OVER ()
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

CREATE FUNCTION platform_invoice_page(
    p_search TEXT, p_status TEXT, p_overdue BOOLEAN, p_from DATE, p_to DATE, p_min NUMERIC, p_max NUMERIC,
    p_limit INT, p_offset INT)
    RETURNS TABLE (id UUID, tenant_id UUID, organisation TEXT, invoice_number TEXT, plan_name TEXT, billing_cycle TEXT,
                   period_starts_at TIMESTAMPTZ, period_ends_at TIMESTAMPTZ, amount NUMERIC, tax_amount NUMERIC,
                   invoice_total NUMERIC, paid NUMERIC, outstanding NUMERIC, status TEXT, issued_on DATE, due_on DATE,
                   total BIGINT)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS
$$
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
REVOKE ALL ON FUNCTION platform_invoice_page(TEXT, TEXT, BOOLEAN, DATE, DATE, NUMERIC, NUMERIC, INT, INT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_invoice_page(TEXT, TEXT, BOOLEAN, DATE, DATE, NUMERIC, NUMERIC, INT, INT) TO ${approle};

CREATE FUNCTION platform_payment_page(
    p_search TEXT, p_status TEXT, p_from DATE, p_to DATE, p_limit INT, p_offset INT)
    RETURNS TABLE (id UUID, tenant_id UUID, organisation TEXT, invoice_id UUID, invoice_number TEXT, provider TEXT,
                   reference TEXT, method TEXT, amount NUMERIC, status TEXT, failure_reason TEXT, paid_on DATE,
                   notes TEXT, recorded_by TEXT, recorded_at TIMESTAMPTZ, total BIGINT)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS
$$
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
REVOKE ALL ON FUNCTION platform_payment_page(TEXT, TEXT, DATE, DATE, INT, INT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_payment_page(TEXT, TEXT, DATE, DATE, INT, INT) TO ${approle};

-- A bank reference pays one invoice, anywhere on the platform and however it was typed. Row-level security would
-- only show this organisation's payments. Repeating it on the same invoice is a retry, which settling makes harmless.
CREATE FUNCTION platform_reference_used_elsewhere(p_reference TEXT, p_invoice UUID) RETURNS BOOLEAN
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS
$$
BEGIN
    PERFORM require_platform_admin();
    RETURN EXISTS (SELECT 1 FROM payment_transactions
                   WHERE upper(provider_reference) = upper(p_reference) AND invoice_id <> p_invoice);
END;
$$;
REVOKE ALL ON FUNCTION platform_reference_used_elsewhere(TEXT, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_reference_used_elsewhere(TEXT, UUID) TO ${approle};

-- A suspended organisation cannot sign in to renew, so it gets no reminders to.
CREATE OR REPLACE FUNCTION tenants_with_subscriptions_ending() RETURNS SETOF UUID
    LANGUAGE sql SECURITY DEFINER SET search_path = public AS
$$
    SELECT s.tenant_id FROM subscriptions s
    JOIN subscription_plans p ON p.id = s.plan_id
    JOIN tenants t ON t.id = s.tenant_id
    WHERE t.status = 'ACTIVE'
      AND s.current_period_ends_at < now() + INTERVAL '7 days'
      AND COALESCE(s.last_notice_days, 8) > -p.grace_days;
$$;

CREATE FUNCTION platform_trial_summary(p_zone TEXT) RETURNS JSONB
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS
$$
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
REVOKE ALL ON FUNCTION platform_trial_summary(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_trial_summary(TEXT) TO ${approle};
