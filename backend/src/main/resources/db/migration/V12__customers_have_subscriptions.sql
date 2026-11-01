-- An organisation without a subscription is the platform's own, kept only because an account must belong to one.
-- It is not a customer, so these counts leave it out. Every other customer figure already starts from subscriptions.

CREATE OR REPLACE FUNCTION platform_analytics(p_start timestamp with time zone, p_end timestamp with time zone, p_zone text) RETURNS jsonb
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
DECLARE
    closing TIMESTAMPTZ := LEAST(p_end, now());
    dashboard JSONB := platform_dashboard(p_start, p_end, p_zone);
    result JSONB;
BEGIN
    WITH opened AS (SELECT * FROM platform_contracts_at(p_start)),
         closed AS (SELECT * FROM platform_contracts_at(closing)),
         first_paid AS (
             SELECT i.tenant_id, min(i.paid_at) AS paid_at FROM subscription_invoices i
             WHERE i.status = 'PAID' GROUP BY i.tenant_id),
         cohort AS (SELECT t.id FROM tenants t
                    WHERE t.created_at >= p_start AND t.created_at < p_end
                      AND EXISTS (SELECT 1 FROM subscriptions s WHERE s.tenant_id = t.id)),
         lost AS (
             SELECT o.tenant_id, GREATEST(o.monthly - COALESCE(c.monthly, 0), 0) AS amount, c.tenant_id IS NULL AS gone
             FROM opened o LEFT JOIN closed c ON c.tenant_id = o.tenant_id)
    SELECT jsonb_build_object(
        'mrr', (SELECT COALESCE(round(sum(monthly), 2), 0) FROM closed),
        'arr', (SELECT COALESCE(round(sum(monthly) * 12, 2), 0) FROM closed),
        'openingMrr', (SELECT COALESCE(round(sum(monthly), 2), 0) FROM opened),
        'openingCustomers', (SELECT count(*) FROM opened),
        'closingCustomers', (SELECT count(*) FROM closed),
        'newCustomers', (SELECT count(*) FROM first_paid f WHERE f.paid_at >= p_start AND f.paid_at < p_end),
        'registered', (SELECT count(*) FROM cohort),
        'converted', (SELECT count(*) FROM cohort c JOIN first_paid f ON f.tenant_id = c.id),
        'trialConversion', (SELECT round(100.0 * count(f.tenant_id) / NULLIF(count(*), 0), 1)
                            FROM cohort c LEFT JOIN first_paid f ON f.tenant_id = c.id),
        'lostCustomers', (SELECT count(*) FROM lost WHERE gone),
        'customerChurn', (SELECT round(100.0 * count(*) FILTER (WHERE gone) / NULLIF(count(*), 0), 1) FROM lost),
        'revenueLost', (SELECT COALESCE(round(sum(amount), 2), 0) FROM lost),
        'revenueChurn', (SELECT round(100 * sum(amount) / NULLIF((SELECT sum(monthly) FROM opened), 0), 1) FROM lost),
        'averageRevenue', (SELECT round(sum(monthly) / NULLIF(count(*), 0), 2) FROM closed),
        'revenueByPlan', (SELECT COALESCE(jsonb_agg(jsonb_build_object('name', x.plan_name, 'mrr', x.mrr)
                                                    ORDER BY x.mrr DESC), '[]')
                          FROM (SELECT plan_name, round(sum(monthly), 2) AS mrr FROM closed GROUP BY plan_name) x)
    ) INTO result;
    RETURN result || jsonb_build_object(
        'outstanding', dashboard -> 'outstanding', 'collected', dashboard -> 'collected',
        'collectionRate', dashboard -> 'collectionRate', 'plans', dashboard -> 'plans',
        'months', dashboard -> 'months');
END;
$$;

CREATE OR REPLACE FUNCTION platform_dashboard(p_start timestamp with time zone, p_end timestamp with time zone, p_zone text) RETURNS jsonb
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
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
                'registered', (SELECT count(*) FROM tenants t
                               WHERE t.created_at >= b.opens AND t.created_at < b.closes
                                 AND EXISTS (SELECT 1 FROM subscriptions s WHERE s.tenant_id = t.id)),
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
