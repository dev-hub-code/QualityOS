-- Platform operators, entitlements, usage, settings, announcements and support.
-- ${approle} is the unprivileged role the application connects as.

SET LOCAL check_function_bodies = off;

-- What the signed-in organisation should see today. Dates are Indian Standard Time, like the rest of the platform.
CREATE FUNCTION current_announcements() RETURNS TABLE(id uuid, title text, message text, severity text)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
    SELECT a.id, a.title, a.message, a.severity
    FROM platform_announcements a
    WHERE a.active
      AND (now() AT TIME ZONE 'Asia/Kolkata')::DATE BETWEEN a.starts_on AND a.ends_on
      AND (a.audience = 'ALL' OR a.audience = (
              SELECT CASE s.status WHEN 'TRIALING' THEN 'TRIAL' ELSE 'PAID' END
              FROM subscriptions s WHERE s.tenant_id = current_tenant_id()))
    ORDER BY CASE a.severity WHEN 'CRITICAL' THEN 0 WHEN 'WARNING' THEN 1 ELSE 2 END, a.starts_on DESC;
$$;

-- The same counts PlanLimits enforces, for every organisation at once.
CREATE FUNCTION organisation_usage_now() RETURNS TABLE(tenant_id uuid, users bigint, facilities bigint, storage_bytes bigint, inspections bigint, attachments bigint)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
    SELECT t.id,
           (SELECT count(*) FROM tenant_memberships m WHERE m.tenant_id = t.id AND m.status = 'ACTIVE')
           + (SELECT count(*) FROM invitations i
              WHERE i.tenant_id = t.id AND i.status = 'PENDING' AND i.expires_at > now()),
           (SELECT count(*) FROM facilities f WHERE f.tenant_id = t.id AND f.archived_at IS NULL),
           (SELECT COALESCE(sum(a.size_bytes), 0)::BIGINT FROM attachments a
            WHERE a.tenant_id = t.id AND a.deleted_at IS NULL),
           (SELECT count(*) FROM inspections n WHERE n.tenant_id = t.id
              AND n.created_at >= date_trunc('month', now() AT TIME ZONE t.timezone) AT TIME ZONE t.timezone),
           (SELECT count(*) FROM attachments a WHERE a.tenant_id = t.id AND a.deleted_at IS NULL)
    FROM tenants t;
$$;

-- docs/PLATFORM_ADMIN.md defines every figure here. The dashboard's own figures are reused, so the two cannot disagree.
CREATE FUNCTION platform_analytics(p_start timestamp with time zone, p_end timestamp with time zone, p_zone text) RETURNS jsonb
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
         cohort AS (SELECT t.id FROM tenants t WHERE t.created_at >= p_start AND t.created_at < p_end),
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


SET default_tablespace = '';

SET default_table_access_method = heap;

CREATE TABLE platform_announcements (
    id uuid NOT NULL,
    title text NOT NULL,
    message text NOT NULL,
    severity text NOT NULL,
    audience text NOT NULL,
    starts_on date NOT NULL,
    ends_on date NOT NULL,
    active boolean NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_announcement_audience CHECK ((audience = ANY (ARRAY['ALL'::text, 'TRIAL'::text, 'PAID'::text]))),
    CONSTRAINT ck_announcement_dates CHECK ((ends_on >= starts_on)),
    CONSTRAINT ck_announcement_severity CHECK ((severity = ANY (ARRAY['INFO'::text, 'WARNING'::text, 'CRITICAL'::text])))
);

CREATE FUNCTION platform_announcement_list() RETURNS SETOF platform_announcements
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
BEGIN
    PERFORM require_platform_admin();
    RETURN QUERY SELECT * FROM platform_announcements ORDER BY ends_on DESC, created_at DESC;
END;
$$;

-- With no id it creates. Returns nothing when the record has moved on, or was never there.
CREATE FUNCTION platform_announcement_save(p_id uuid, p_title text, p_message text, p_severity text, p_audience text, p_starts date, p_ends date, p_active boolean, p_version bigint) RETURNS uuid
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
DECLARE
    saved UUID;
BEGIN
    PERFORM require_platform_role(ARRAY['SUPER_ADMIN', 'SUPPORT_ADMIN']);
    IF p_id IS NULL THEN
        INSERT INTO platform_announcements (id, title, message, severity, audience, starts_on, ends_on, active)
        VALUES (gen_random_uuid(), p_title, p_message, p_severity, p_audience, p_starts, p_ends, p_active)
        RETURNING id INTO saved;
    ELSE
        UPDATE platform_announcements
        SET title = p_title, message = p_message, severity = p_severity, audience = p_audience, starts_on = p_starts,
            ends_on = p_ends, active = p_active, version = version + 1, updated_at = now()
        WHERE id = p_id AND version = p_version
        RETURNING id INTO saved;
    END IF;
    RETURN saved;
END;
$$;

-- Only what operators did to the platform or its customers: not their own work in their own organisation, and not
-- anyone else's trail. An action taken on an organisation is stored as PLATFORM_ACTION, with its name inside.
CREATE FUNCTION platform_audit_page(p_search text, p_action text, p_entity text, p_from timestamp with time zone, p_to timestamp with time zone, p_limit integer, p_offset integer) RETURNS TABLE(id uuid, occurred_at timestamp with time zone, actor text, action text, entity_type text, entity_id text, organisation text, previous_state text, new_state text, reason text, correlation_id text, ip_address text, total bigint)
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
#variable_conflict use_column
DECLARE
    pattern TEXT := '%' || replace(replace(replace(COALESCE(p_search, ''), '\', '\\'), '%', '\%'), '_', '\_') || '%';
BEGIN
    PERFORM require_platform_role(ARRAY['SUPER_ADMIN', 'READ_ONLY_AUDITOR']);
    RETURN QUERY
    SELECT a.id, a.occurred_at, u.email, COALESCE(a.new_state ->> 'action', a.action), a.entity_type, a.entity_id,
           CASE WHEN a.entity_type IN ('SubscriptionPlan', 'FeatureDefinition', 'PlatformOperator', 'PlatformAnalytics',
                                     'PlatformSettings', 'Announcement', 'SupportTicket')
                THEN 'Platform' ELSE t.name END,
           a.previous_state::TEXT, a.new_state::TEXT, a.new_state ->> 'reason', a.correlation_id, a.ip_address,
           count(*) OVER ()
    FROM audit_logs a
    JOIN platform_admins o ON o.user_id = a.user_id
    LEFT JOIN users u ON u.id = a.user_id
    LEFT JOIN tenants t ON t.id = a.tenant_id
    WHERE a.entity_type = ANY (ARRAY['SubscriptionPlan', 'FeatureDefinition', 'PlatformOperator', 'PlatformAnalytics',
                                     'Organisation', 'Subscription', 'SubscriptionInvoice', 'PaymentTransaction',
                                     'Notification', 'Organization', 'PlatformSettings', 'Announcement',
                                     'SupportTicket'])
      AND a.action NOT IN ('ORGANIZATION_CREATED', 'ORGANIZATION_UPDATED', 'TENANT_SWITCHED')
      AND (p_action IS NULL OR COALESCE(a.new_state ->> 'action', a.action) = p_action)
      AND (p_entity IS NULL OR a.entity_type = p_entity)
      AND (p_from IS NULL OR a.occurred_at >= p_from)
      AND (p_to IS NULL OR a.occurred_at < p_to)
      AND (p_search IS NULL OR u.email ILIKE pattern OR t.name ILIKE pattern OR a.entity_id ILIKE pattern
           OR a.correlation_id ILIKE pattern)
    ORDER BY a.occurred_at DESC, a.id DESC
    LIMIT p_limit OFFSET p_offset;
END;
$$;

-- What was under contract at a moment: the latest paid invoice covering it, for each organisation, without tax and
-- with an annual invoice spread over twelve months. docs/PLATFORM_ADMIN.md defines every figure built on this.
CREATE FUNCTION platform_contracts_at(p_at timestamp with time zone) RETURNS TABLE(tenant_id uuid, plan_name text, monthly numeric)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
    SELECT DISTINCT ON (i.tenant_id) i.tenant_id, i.plan_name,
           CASE i.billing_cycle WHEN 'ANNUAL' THEN i.amount / 12 ELSE i.amount END
    FROM subscription_invoices i
    JOIN subscriptions s ON s.tenant_id = i.tenant_id
    WHERE i.status = 'PAID' AND i.period_starts_at <= p_at AND i.period_ends_at > p_at
      AND (s.cancelled_at IS NULL OR s.cancelled_at > p_at)
    ORDER BY i.tenant_id, i.paid_at DESC;
$$;

CREATE FUNCTION platform_dashboard(p_start timestamp with time zone, p_end timestamp with time zone, p_zone text) RETURNS jsonb
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

-- One row per email, however many attempts it took. Only a notification that gave up can be sent again.
CREATE FUNCTION platform_email_page(p_search text, p_status text, p_retryable boolean, p_limit integer, p_offset integer) RETURNS TABLE(id uuid, tenant_id uuid, organisation text, notification_id uuid, recipient text, template text, status text, attempts bigint, last_attempt timestamp with time zone, error_type text, retryable boolean, total bigint)
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
#variable_conflict use_column
DECLARE
    pattern TEXT := '%' || replace(replace(replace(COALESCE(p_search, ''), '\', '\\'), '%', '\%'), '_', '\_') || '%';
BEGIN
    PERFORM require_platform_admin();
    RETURN QUERY
    WITH sends AS (
        SELECT l.*, row_number() OVER w AS latest, count(*) OVER w AS attempts
        FROM email_delivery_logs l
        WHERE p_search IS NULL OR l.recipient ILIKE pattern OR l.template ILIKE pattern
        WINDOW w AS (PARTITION BY COALESCE(l.notification_id, l.id) ORDER BY l.created_at DESC, l.id DESC
                     ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING)
    ), listed AS (
        SELECT s.*, COALESCE(n.email_status = 'FAILED', FALSE) AS retryable
        FROM sends s LEFT JOIN notifications n ON n.id = s.notification_id
        WHERE s.latest = 1
    )
    SELECT l.id, l.tenant_id, t.name, l.notification_id, l.recipient, l.template, l.status, l.attempts, l.created_at,
           l.error_type, l.retryable, count(*) OVER ()
    FROM listed l
    LEFT JOIN tenants t ON t.id = l.tenant_id
    WHERE (p_status IS NULL OR l.status = p_status)
      AND (p_retryable IS NOT TRUE OR l.retryable)
    ORDER BY l.created_at DESC, l.id DESC
    LIMIT p_limit OFFSET p_offset;
END;
$$;

-- How many organisations have each feature, through their current plan.
CREATE FUNCTION platform_feature_counts() RETURNS TABLE(code text, organisations bigint)
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
#variable_conflict use_column
BEGIN
    PERFORM require_platform_admin();
    RETURN QUERY
    SELECT f.code, count(*)
    FROM subscriptions s
    JOIN subscription_plans p ON p.id = s.plan_id
    CROSS JOIN LATERAL unnest(p.features) AS f (code)
    GROUP BY f.code;
END;
$$;

CREATE FUNCTION platform_feature_organisations(p_code text, p_limit integer, p_offset integer) RETURNS TABLE(tenant_id uuid, name text, plan_name text, total bigint)
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
#variable_conflict use_column
BEGIN
    PERFORM require_platform_admin();
    RETURN QUERY
    SELECT t.id, t.name, p.name, count(*) OVER ()
    FROM subscriptions s
    JOIN subscription_plans p ON p.id = s.plan_id
    JOIN tenants t ON t.id = s.tenant_id
    WHERE p_code = ANY (p.features)
    ORDER BY t.name, t.id
    LIMIT p_limit OFFSET p_offset;
END;
$$;

-- Call only once the password has been checked.
CREATE FUNCTION platform_mark_reauthenticated(p_minutes integer) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
BEGIN
    PERFORM require_platform_admin();
    UPDATE platform_admins SET reauthenticated_until = now() + make_interval(mins => p_minutes)
    WHERE user_id = current_app_user_id();
END;
$$;

-- Billing notices only. Quality notifications stay with the organisation.
CREATE FUNCTION platform_notification_page(p_type text, p_limit integer, p_offset integer) RETURNS TABLE(id uuid, sent_at timestamp with time zone, type text, title text, organisation text, tenant_id uuid, recipient text, email_status text, total bigint)
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
#variable_conflict use_column
BEGIN
    PERFORM require_platform_admin();
    RETURN QUERY
    SELECT n.id, n.created_at, n.type, n.title, t.name, n.tenant_id, u.email, n.email_status, count(*) OVER ()
    FROM notifications n
    JOIN tenants t ON t.id = n.tenant_id
    LEFT JOIN users u ON u.id = n.user_id
    WHERE n.type = ANY (ARRAY['SUBSCRIPTION_EXPIRY', 'PAYMENT_RECEIVED', 'INVOICE_OVERDUE', 'PLAN_CHANGED'])
      AND (p_type IS NULL OR n.type = p_type)
    ORDER BY n.created_at DESC, n.id DESC
    LIMIT p_limit OFFSET p_offset;
END;
$$;

-- The application cannot write platform_admins, so a mistake in it cannot grant access. These functions can, and each
-- insists on an active SUPER_ADMIN at the database. Each returns how it went, for the application to report.
CREATE FUNCTION platform_operator_add(p_email text, p_role text) RETURNS text
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
DECLARE
    target UUID;
BEGIN
    PERFORM require_platform_role(ARRAY['SUPER_ADMIN']);
    SELECT id INTO target FROM users WHERE email = lower(p_email) AND status = 'ACTIVE';
    IF target IS NULL THEN
        RETURN 'NO_ACCOUNT';
    END IF;
    IF EXISTS (SELECT 1 FROM platform_admins WHERE user_id = target) THEN
        RETURN 'ALREADY_OPERATOR';
    END IF;
    INSERT INTO platform_admins (user_id, role, added_by) VALUES (target, p_role, current_app_user_id());
    RETURN target::TEXT;
END;
$$;

-- Locks every active SUPER_ADMIN, so that two of them removing each other at once cannot leave none.
CREATE FUNCTION platform_operator_change(p_user uuid, p_role text, p_active boolean) RETURNS text
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
DECLARE
    current_row platform_admins%ROWTYPE;
    remaining BIGINT;
BEGIN
    PERFORM require_platform_role(ARRAY['SUPER_ADMIN']);
    PERFORM 1 FROM platform_admins WHERE role = 'SUPER_ADMIN' AND deactivated_at IS NULL ORDER BY user_id FOR UPDATE;
    SELECT * INTO current_row FROM platform_admins WHERE user_id = p_user FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'NOT_FOUND';
    END IF;
    SELECT count(*) INTO remaining FROM platform_admins
    WHERE role = 'SUPER_ADMIN' AND deactivated_at IS NULL AND user_id <> p_user;
    IF remaining = 0 AND current_row.role = 'SUPER_ADMIN' AND current_row.deactivated_at IS NULL
       AND (NOT p_active OR p_role <> 'SUPER_ADMIN') THEN
        RETURN 'LAST_SUPER_ADMIN';
    END IF;
    UPDATE platform_admins
    SET role = p_role,
        deactivated_at = CASE WHEN p_active THEN NULL ELSE COALESCE(deactivated_at, now()) END,
        reauthenticated_until = CASE WHEN p_active THEN reauthenticated_until END
    WHERE user_id = p_user;
    RETURN 'OK';
END;
$$;

CREATE FUNCTION platform_operators() RETURNS TABLE(user_id uuid, email text, full_name text, role text, added_at timestamp with time zone, deactivated_at timestamp with time zone, added_by text, last_activity timestamp with time zone)
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
#variable_conflict use_column
BEGIN
    PERFORM require_platform_role(ARRAY['SUPER_ADMIN', 'READ_ONLY_AUDITOR']);
    RETURN QUERY
    SELECT a.user_id, u.email, u.full_name, a.role, a.created_at, a.deactivated_at, b.email,
           (SELECT max(l.occurred_at) FROM audit_logs l WHERE l.user_id = a.user_id)
    FROM platform_admins a
    JOIN users u ON u.id = a.user_id
    LEFT JOIN users b ON b.id = a.added_by
    ORDER BY a.deactivated_at IS NOT NULL, u.email;
END;
$$;

CREATE FUNCTION platform_organisation_page(p_search text, p_plan text, p_state text, p_unpaid boolean, p_from timestamp with time zone, p_to timestamp with time zone, p_sort text, p_limit integer, p_offset integer) RETURNS TABLE(tenant_id uuid, subscription_id uuid, name text, gstin text, contact_email text, plan_code text, plan_name text, state text, billing_cycle text, ends_at timestamp with time zone, cancelled_at timestamp with time zone, suspended_at timestamp with time zone, registered_at timestamp with time zone, open_invoices bigint, next_due date, total bigint)
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
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

CREATE FUNCTION platform_recently_reauthenticated() RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
    SELECT EXISTS (SELECT 1 FROM platform_admins
                   WHERE user_id = current_app_user_id() AND deactivated_at IS NULL
                     AND reauthenticated_until > now());
$$;

CREATE FUNCTION platform_settings_save(p_trial integer, p_grace integer, p_maintenance boolean, p_registration boolean, p_support text, p_version bigint) RETURNS boolean
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
BEGIN
    PERFORM require_platform_role(ARRAY['SUPER_ADMIN']);
    UPDATE platform_settings
    SET default_trial_days = p_trial, default_grace_days = p_grace, maintenance_mode = p_maintenance,
        registration_open = p_registration, support_email = p_support, version = version + 1, updated_at = now()
    WHERE version = p_version;
    RETURN FOUND;
END;
$$;

CREATE FUNCTION platform_state(p_status text, p_ends timestamp with time zone, p_grace integer, p_at timestamp with time zone) RETURNS text
    LANGUAGE sql IMMUTABLE
    AS $$
    SELECT CASE
        WHEN p_at < p_ends THEN CASE p_status WHEN 'TRIALING' THEN 'TRIAL' ELSE 'ACTIVE' END
        WHEN p_at < p_ends + make_interval(days => p_grace) THEN 'GRACE'
        ELSE 'READ_ONLY'
    END;
$$;

CREATE FUNCTION platform_subscriber_count(p_plan uuid) RETURNS bigint
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
BEGIN
    PERFORM require_platform_admin();
    RETURN (SELECT count(*) FROM subscriptions WHERE plan_id = p_plan);
END;
$$;

-- Failed emails are the only errors that can be tied to one organisation safely. They carry no record content.
CREATE FUNCTION platform_support_errors(p_tenant uuid) RETURNS TABLE(occurred_at timestamp with time zone, template text, error_type text)
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
#variable_conflict use_column
BEGIN
    PERFORM require_platform_admin();
    RETURN QUERY
    SELECT l.created_at, l.template, l.error_type
    FROM email_delivery_logs l
    WHERE l.tenant_id = p_tenant AND l.status = 'FAILED' AND l.created_at > now() - INTERVAL '30 days'
    ORDER BY l.created_at DESC
    LIMIT 20;
END;
$$;

CREATE FUNCTION platform_support_note(p_id uuid, p_body text) RETURNS text
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
DECLARE
    ticket support_tickets%ROWTYPE;
BEGIN
    PERFORM require_platform_role(ARRAY['SUPER_ADMIN', 'SUPPORT_ADMIN']);
    SELECT * INTO ticket FROM support_tickets WHERE id = p_id FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'NOT_FOUND';
    END IF;
    IF ticket.status = 'CLOSED' THEN
        RETURN 'CLOSED';
    END IF;
    INSERT INTO support_notes (id, ticket_id, author_id, body)
    VALUES (gen_random_uuid(), p_id, current_app_user_id(), p_body);
    UPDATE support_tickets SET updated_at = now() WHERE id = p_id;
    RETURN 'OK';
END;
$$;

CREATE FUNCTION platform_support_notes(p_id uuid) RETURNS TABLE(id uuid, author text, body text, created_at timestamp with time zone)
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
#variable_conflict use_column
BEGIN
    PERFORM require_platform_admin();
    RETURN QUERY
    SELECT n.id, u.email, n.body, n.created_at
    FROM support_notes n JOIN users u ON u.id = n.author_id
    WHERE n.ticket_id = p_id
    ORDER BY n.created_at, n.id;
END;
$$;

CREATE FUNCTION platform_support_open(p_tenant uuid, p_subject text, p_note text) RETURNS uuid
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
DECLARE
    ticket UUID := gen_random_uuid();
BEGIN
    PERFORM require_platform_role(ARRAY['SUPER_ADMIN', 'SUPPORT_ADMIN']);
    IF NOT EXISTS (SELECT 1 FROM tenants WHERE id = p_tenant) THEN
        RETURN NULL;
    END IF;
    INSERT INTO support_tickets (id, tenant_id, subject, opened_by)
    VALUES (ticket, p_tenant, p_subject, current_app_user_id());
    INSERT INTO support_notes (id, ticket_id, author_id, body)
    VALUES (gen_random_uuid(), ticket, current_app_user_id(), p_note);
    RETURN ticket;
END;
$$;

-- A closed ticket stays closed, so what it says cannot change afterwards.
CREATE FUNCTION platform_support_status(p_id uuid, p_status text, p_version bigint) RETURNS text
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
DECLARE
    ticket support_tickets%ROWTYPE;
BEGIN
    PERFORM require_platform_role(ARRAY['SUPER_ADMIN', 'SUPPORT_ADMIN']);
    SELECT * INTO ticket FROM support_tickets WHERE id = p_id FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'NOT_FOUND';
    END IF;
    IF ticket.status = 'CLOSED' THEN
        RETURN 'CLOSED';
    END IF;
    IF ticket.version <> p_version THEN
        RETURN 'STALE';
    END IF;
    UPDATE support_tickets SET status = p_status, version = version + 1, updated_at = now() WHERE id = p_id;
    RETURN 'OK';
END;
$$;

CREATE FUNCTION platform_support_tickets(p_tenant uuid, p_status text, p_limit integer, p_offset integer) RETURNS TABLE(id uuid, tenant_id uuid, organisation text, subject text, status text, opened_by text, created_at timestamp with time zone, updated_at timestamp with time zone, version bigint, total bigint)
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
#variable_conflict use_column
BEGIN
    PERFORM require_platform_admin();
    RETURN QUERY
    SELECT s.id, s.tenant_id, t.name, s.subject, s.status, u.email, s.created_at, s.updated_at, s.version,
           count(*) OVER ()
    FROM support_tickets s
    JOIN tenants t ON t.id = s.tenant_id
    JOIN users u ON u.id = s.opened_by
    WHERE (p_tenant IS NULL OR s.tenant_id = p_tenant)
      AND (p_status IS NULL OR s.status = p_status)
    ORDER BY s.updated_at DESC, s.id DESC
    LIMIT p_limit OFFSET p_offset;
END;
$$;

CREATE FUNCTION platform_usage_history(p_tenant uuid, p_days integer) RETURNS TABLE(taken_on date, users bigint, facilities bigint, storage_bytes bigint, inspections bigint, attachments bigint)
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
#variable_conflict use_column
BEGIN
    PERFORM require_platform_admin();
    RETURN QUERY
    SELECT h.taken_on, h.users, h.facilities, h.storage_bytes, h.inspections, h.attachments
    FROM organisation_usage_snapshots h
    WHERE h.tenant_id = p_tenant AND h.taken_on > current_date - p_days
    ORDER BY h.taken_on DESC;
END;
$$;

CREATE FUNCTION platform_usage_page(p_search text, p_alert text, p_limit integer, p_offset integer) RETURNS TABLE(tenant_id uuid, name text, plan_id uuid, plan_name text, display_order integer, features text, users bigint, max_users integer, facilities bigint, max_facilities integer, storage_bytes bigint, max_storage_mb integer, inspections bigint, max_monthly_inspections integer, attachments bigint, max_attachments integer, highest numeric, total bigint)
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
#variable_conflict use_column
DECLARE
    pattern TEXT := '%' || replace(replace(replace(COALESCE(p_search, ''), '\', '\\'), '%', '\%'), '_', '\_') || '%';
BEGIN
    PERFORM require_platform_admin();
    RETURN QUERY
    WITH measured AS (
        SELECT t.id, t.name, p.id AS plan_id, p.name AS plan_name, p.display_order,
               array_to_string(p.features, ',') AS features, u.users, p.max_users, u.facilities, p.max_facilities,
               u.storage_bytes, p.max_storage_mb, u.inspections, p.max_monthly_inspections, u.attachments,
               p.max_attachments,
               GREATEST(usage_share(u.users, p.max_users), usage_share(u.facilities, p.max_facilities),
                        usage_share(u.storage_bytes / 1048576.0, p.max_storage_mb),
                        usage_share(u.inspections, p.max_monthly_inspections),
                        usage_share(u.attachments, p.max_attachments)) AS highest
        FROM organisation_usage_now() u
        JOIN tenants t ON t.id = u.tenant_id
        JOIN subscriptions s ON s.tenant_id = t.id
        JOIN subscription_plans p ON p.id = s.plan_id
        WHERE p_search IS NULL OR t.name ILIKE pattern
    )
    SELECT m.id, m.name, m.plan_id, m.plan_name, m.display_order, m.features, m.users, m.max_users, m.facilities,
           m.max_facilities, m.storage_bytes, m.max_storage_mb, m.inspections, m.max_monthly_inspections,
           m.attachments, m.max_attachments, round(m.highest, 4), count(*) OVER ()
    FROM measured m
    WHERE p_alert IS NULL
       OR (p_alert = 'NEAR' AND m.highest >= 0.8 AND m.highest < 1)
       OR (p_alert = 'REACHED' AND m.highest >= 1)
    ORDER BY m.highest DESC, m.name, m.id
    LIMIT p_limit OFFSET p_offset;
END;
$$;

-- Safe to run again on the same day: the day's row is replaced.
CREATE FUNCTION record_usage_snapshots() RETURNS integer
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
DECLARE
    written INTEGER;
BEGIN
    INSERT INTO organisation_usage_snapshots
    SELECT u.tenant_id, (now() AT TIME ZONE 'Asia/Kolkata')::DATE, u.users, u.facilities, u.storage_bytes,
           u.inspections, u.attachments
    FROM organisation_usage_now() u
    JOIN subscriptions s ON s.tenant_id = u.tenant_id
    ON CONFLICT (tenant_id, taken_on) DO UPDATE SET
        users = excluded.users, facilities = excluded.facilities, storage_bytes = excluded.storage_bytes,
        inspections = excluded.inspections, attachments = excluded.attachments;
    GET DIAGNOSTICS written = ROW_COUNT;
    RETURN written;
END;
$$;

-- The functions below answer for every organisation, so each one refuses anyone who is not an administrator,
-- whatever the application above it has checked.
CREATE FUNCTION require_platform_admin() RETURNS void
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM platform_admins
                   WHERE user_id = current_app_user_id() AND deactivated_at IS NULL) THEN
        RAISE EXCEPTION 'platform administrator required' USING ERRCODE = 'insufficient_privilege';
    END IF;
END;
$$;

CREATE FUNCTION require_platform_role(p_roles text[]) RETURNS void
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM platform_admins
                   WHERE user_id = current_app_user_id() AND deactivated_at IS NULL AND role = ANY (p_roles)) THEN
        RAISE EXCEPTION 'platform role required' USING ERRCODE = 'insufficient_privilege';
    END IF;
END;
$$;

CREATE FUNCTION usage_share(p_used numeric, p_limit numeric) RETURNS numeric
    LANGUAGE sql IMMUTABLE
    AS $$
    SELECT CASE WHEN p_limit IS NULL THEN 0 ELSE p_used / p_limit END;
$$;

-- What a plan may include. Whether a feature is actually enforced is decided in PlanAccess, not here.
CREATE TABLE feature_definitions (
    id uuid NOT NULL,
    code text NOT NULL,
    name text NOT NULL,
    description text DEFAULT ''::text NOT NULL,
    value_type text DEFAULT 'BOOLEAN'::text NOT NULL,
    active boolean DEFAULT true NOT NULL,
    default_enabled boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_feature_value_type CHECK ((value_type = ANY (ARRAY['BOOLEAN'::text, 'NUMBER'::text, 'TEXT'::text, 'STRUCTURED'::text])))
);

-- One row per organisation per day. Only the job writes these, through the function below.
CREATE TABLE organisation_usage_snapshots (
    tenant_id uuid NOT NULL,
    taken_on date NOT NULL,
    users bigint NOT NULL,
    facilities bigint NOT NULL,
    storage_bytes bigint NOT NULL,
    inspections bigint NOT NULL,
    attachments bigint NOT NULL
);

ALTER TABLE ONLY organisation_usage_snapshots FORCE ROW LEVEL SECURITY;

CREATE TABLE platform_settings (
    id integer DEFAULT 1 NOT NULL,
    default_trial_days integer DEFAULT 14 NOT NULL,
    default_grace_days integer DEFAULT 7 NOT NULL,
    maintenance_mode boolean DEFAULT false NOT NULL,
    registration_open boolean DEFAULT true NOT NULL,
    support_email text,
    version bigint DEFAULT 0 NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_platform_settings_grace CHECK (((default_grace_days >= 0) AND (default_grace_days <= 90))),
    CONSTRAINT ck_platform_settings_single CHECK ((id = 1)),
    CONSTRAINT ck_platform_settings_trial CHECK (((default_trial_days >= 0) AND (default_trial_days <= 365)))
);

-- Each saved state of a plan. Nothing may change or remove one.
CREATE TABLE subscription_plan_versions (
    id uuid NOT NULL,
    plan_id uuid NOT NULL,
    version bigint NOT NULL,
    snapshot jsonb NOT NULL,
    reason text,
    changed_by uuid,
    changed_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE support_notes (
    id uuid NOT NULL,
    ticket_id uuid NOT NULL,
    author_id uuid NOT NULL,
    body text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY support_notes FORCE ROW LEVEL SECURITY;

-- Private to operators. The application role has no rights on either table, so no tenant query can reach a note.
CREATE TABLE support_tickets (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    subject text NOT NULL,
    status text DEFAULT 'OPEN'::text NOT NULL,
    opened_by uuid NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_support_ticket_status CHECK ((status = ANY (ARRAY['OPEN'::text, 'IN_PROGRESS'::text, 'WAITING_FOR_CUSTOMER'::text, 'RESOLVED'::text, 'CLOSED'::text])))
);

ALTER TABLE ONLY support_tickets FORCE ROW LEVEL SECURITY;

-- Starting rows, loaded before row-level security is switched on.
INSERT INTO feature_definitions VALUES ('0b48e737-99c0-4ccb-9633-ec11c083e5ed', 'NCR_CAPA', 'NCR and CAPA', 'Raising and working non-conformances and corrective actions', 'BOOLEAN', true, false, '2026-10-31 22:37:43.168051+00', '2026-10-31 22:37:43.168051+00', NULL, NULL, 0);
INSERT INTO feature_definitions VALUES ('de59575b-744b-4cfa-b55c-8936d7b74b24', 'REPORTS', 'Dashboards and reports', 'Quality dashboards, analytics and report exports', 'BOOLEAN', true, false, '2026-10-31 22:37:43.168051+00', '2026-10-31 22:37:43.168051+00', NULL, NULL, 0);
INSERT INTO feature_definitions VALUES ('7a1799c4-292d-4cb6-8016-6aa0075fea5b', 'ADVANCED', 'Traceability, recalls, calibration and audits', 'Lot tracing, recalls, instrument calibration and quality audits', 'BOOLEAN', true, false, '2026-10-31 22:37:43.168051+00', '2026-10-31 22:37:43.168051+00', NULL, NULL, 0);
INSERT INTO platform_settings VALUES (1, 14, 7, false, true, NULL, 0, '2026-10-31 22:37:43.530371+00');
INSERT INTO subscription_plan_versions VALUES ('419158ed-f8b3-46a4-83fa-52b925695132', 'dce65d14-bd62-47a9-aa36-be51777f4173', 0, '{"name": "Starter", "active": true, "features": [], "maxUsers": 5, "graceDays": 7, "trialDays": 14, "taxPercent": 18.00, "annualPrice": 29990.00, "description": "Basic inspections and suppliers", "monthlyPrice": 2999.00, "maxFacilities": 1, "publicVisible": true}', 'State when history began', NULL, '2026-10-31 22:37:42.849778+00');
INSERT INTO subscription_plan_versions VALUES ('1147664e-f059-4359-8d46-4b14e3be0f6f', '95a07e4f-55c4-4d5b-983e-a1087cc99040', 0, '{"name": "Growth", "active": true, "features": ["NCR_CAPA", "REPORTS"], "maxUsers": 15, "graceDays": 7, "trialDays": 14, "taxPercent": 18.00, "annualPrice": 59990.00, "description": "Inspections, NCR, CAPA and reports", "monthlyPrice": 5999.00, "maxFacilities": 2, "publicVisible": true}', 'State when history began', NULL, '2026-10-31 22:37:42.849778+00');
INSERT INTO subscription_plan_versions VALUES ('bee59137-42b5-4e1f-9d90-e829f9be5cce', '0c72cfc7-354f-4cbe-9c80-dc26bb6e1a5a', 0, '{"name": "Professional", "active": true, "features": ["NCR_CAPA", "REPORTS", "ADVANCED"], "maxUsers": 50, "graceDays": 7, "trialDays": 14, "taxPercent": 18.00, "annualPrice": 99990.00, "description": "Advanced workflows and analytics", "monthlyPrice": 9999.00, "maxFacilities": 5, "publicVisible": true}', 'State when history began', NULL, '2026-10-31 22:37:42.849778+00');
INSERT INTO subscription_plan_versions VALUES ('b8ddc4e9-e2ae-4867-859e-93b673302069', '52185dbf-7654-4a33-b5fd-a2cbd088b482', 0, '{"name": "Enterprise", "active": true, "features": ["NCR_CAPA", "REPORTS", "ADVANCED"], "maxUsers": null, "graceDays": 7, "trialDays": 14, "taxPercent": 18.00, "annualPrice": null, "description": "Multi-facility and advanced features", "monthlyPrice": null, "maxFacilities": null, "publicVisible": true}', 'State when history began', NULL, '2026-10-31 22:37:42.849778+00');

ALTER TABLE ONLY feature_definitions
    ADD CONSTRAINT feature_definitions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY organisation_usage_snapshots
    ADD CONSTRAINT organisation_usage_snapshots_pkey PRIMARY KEY (tenant_id, taken_on);

ALTER TABLE ONLY platform_announcements
    ADD CONSTRAINT platform_announcements_pkey PRIMARY KEY (id);

ALTER TABLE ONLY platform_settings
    ADD CONSTRAINT platform_settings_pkey PRIMARY KEY (id);

ALTER TABLE ONLY subscription_plan_versions
    ADD CONSTRAINT subscription_plan_versions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY support_notes
    ADD CONSTRAINT support_notes_pkey PRIMARY KEY (id);

ALTER TABLE ONLY support_tickets
    ADD CONSTRAINT support_tickets_pkey PRIMARY KEY (id);

ALTER TABLE ONLY feature_definitions
    ADD CONSTRAINT uq_feature_code UNIQUE (code);

ALTER TABLE ONLY subscription_plan_versions
    ADD CONSTRAINT uq_plan_versions UNIQUE (plan_id, version);

CREATE INDEX ix_support_notes_ticket ON support_notes USING btree (ticket_id, created_at);

CREATE INDEX ix_support_tickets_status ON support_tickets USING btree (status, updated_at DESC);

CREATE INDEX ix_support_tickets_tenant ON support_tickets USING btree (tenant_id, updated_at DESC);

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON feature_definitions FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE ONLY organisation_usage_snapshots
    ADD CONSTRAINT organisation_usage_snapshots_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY subscription_plan_versions
    ADD CONSTRAINT subscription_plan_versions_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES subscription_plans(id);

ALTER TABLE ONLY support_notes
    ADD CONSTRAINT support_notes_author_id_fkey FOREIGN KEY (author_id) REFERENCES users(id);

ALTER TABLE ONLY support_notes
    ADD CONSTRAINT support_notes_ticket_id_fkey FOREIGN KEY (ticket_id) REFERENCES support_tickets(id);

ALTER TABLE ONLY support_tickets
    ADD CONSTRAINT support_tickets_opened_by_fkey FOREIGN KEY (opened_by) REFERENCES users(id);

ALTER TABLE ONLY support_tickets
    ADD CONSTRAINT support_tickets_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE organisation_usage_snapshots ENABLE ROW LEVEL SECURITY;

CREATE POLICY platform_only ON support_notes TO "${flyway:user}" USING (true) WITH CHECK (true);

CREATE POLICY platform_only ON support_tickets TO "${flyway:user}" USING (true) WITH CHECK (true);

ALTER TABLE support_notes ENABLE ROW LEVEL SECURITY;

ALTER TABLE support_tickets ENABLE ROW LEVEL SECURITY;

CREATE POLICY usage_job ON organisation_usage_snapshots TO "${flyway:user}" USING (true) WITH CHECK (true);

REVOKE ALL ON FUNCTION current_announcements() FROM PUBLIC;
GRANT ALL ON FUNCTION current_announcements() TO ${approle};

REVOKE ALL ON FUNCTION organisation_usage_now() FROM PUBLIC;

REVOKE ALL ON FUNCTION platform_analytics(p_start timestamp with time zone, p_end timestamp with time zone, p_zone text) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_analytics(p_start timestamp with time zone, p_end timestamp with time zone, p_zone text) TO ${approle};

REVOKE ALL ON FUNCTION platform_announcement_list() FROM PUBLIC;
GRANT ALL ON FUNCTION platform_announcement_list() TO ${approle};

REVOKE ALL ON FUNCTION platform_announcement_save(p_id uuid, p_title text, p_message text, p_severity text, p_audience text, p_starts date, p_ends date, p_active boolean, p_version bigint) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_announcement_save(p_id uuid, p_title text, p_message text, p_severity text, p_audience text, p_starts date, p_ends date, p_active boolean, p_version bigint) TO ${approle};

REVOKE ALL ON FUNCTION platform_audit_page(p_search text, p_action text, p_entity text, p_from timestamp with time zone, p_to timestamp with time zone, p_limit integer, p_offset integer) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_audit_page(p_search text, p_action text, p_entity text, p_from timestamp with time zone, p_to timestamp with time zone, p_limit integer, p_offset integer) TO ${approle};

REVOKE ALL ON FUNCTION platform_contracts_at(p_at timestamp with time zone) FROM PUBLIC;

REVOKE ALL ON FUNCTION platform_dashboard(p_start timestamp with time zone, p_end timestamp with time zone, p_zone text) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_dashboard(p_start timestamp with time zone, p_end timestamp with time zone, p_zone text) TO ${approle};

REVOKE ALL ON FUNCTION platform_email_page(p_search text, p_status text, p_retryable boolean, p_limit integer, p_offset integer) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_email_page(p_search text, p_status text, p_retryable boolean, p_limit integer, p_offset integer) TO ${approle};

REVOKE ALL ON FUNCTION platform_feature_counts() FROM PUBLIC;
GRANT ALL ON FUNCTION platform_feature_counts() TO ${approle};

REVOKE ALL ON FUNCTION platform_feature_organisations(p_code text, p_limit integer, p_offset integer) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_feature_organisations(p_code text, p_limit integer, p_offset integer) TO ${approle};

REVOKE ALL ON FUNCTION platform_mark_reauthenticated(p_minutes integer) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_mark_reauthenticated(p_minutes integer) TO ${approle};

REVOKE ALL ON FUNCTION platform_notification_page(p_type text, p_limit integer, p_offset integer) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_notification_page(p_type text, p_limit integer, p_offset integer) TO ${approle};

REVOKE ALL ON FUNCTION platform_operator_add(p_email text, p_role text) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_operator_add(p_email text, p_role text) TO ${approle};

REVOKE ALL ON FUNCTION platform_operator_change(p_user uuid, p_role text, p_active boolean) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_operator_change(p_user uuid, p_role text, p_active boolean) TO ${approle};

REVOKE ALL ON FUNCTION platform_operators() FROM PUBLIC;
GRANT ALL ON FUNCTION platform_operators() TO ${approle};

REVOKE ALL ON FUNCTION platform_organisation_page(p_search text, p_plan text, p_state text, p_unpaid boolean, p_from timestamp with time zone, p_to timestamp with time zone, p_sort text, p_limit integer, p_offset integer) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_organisation_page(p_search text, p_plan text, p_state text, p_unpaid boolean, p_from timestamp with time zone, p_to timestamp with time zone, p_sort text, p_limit integer, p_offset integer) TO ${approle};

REVOKE ALL ON FUNCTION platform_recently_reauthenticated() FROM PUBLIC;
GRANT ALL ON FUNCTION platform_recently_reauthenticated() TO ${approle};

REVOKE ALL ON FUNCTION platform_settings_save(p_trial integer, p_grace integer, p_maintenance boolean, p_registration boolean, p_support text, p_version bigint) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_settings_save(p_trial integer, p_grace integer, p_maintenance boolean, p_registration boolean, p_support text, p_version bigint) TO ${approle};

REVOKE ALL ON FUNCTION platform_subscriber_count(p_plan uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_subscriber_count(p_plan uuid) TO ${approle};

REVOKE ALL ON FUNCTION platform_support_errors(p_tenant uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_support_errors(p_tenant uuid) TO ${approle};

REVOKE ALL ON FUNCTION platform_support_note(p_id uuid, p_body text) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_support_note(p_id uuid, p_body text) TO ${approle};

REVOKE ALL ON FUNCTION platform_support_notes(p_id uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_support_notes(p_id uuid) TO ${approle};

REVOKE ALL ON FUNCTION platform_support_open(p_tenant uuid, p_subject text, p_note text) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_support_open(p_tenant uuid, p_subject text, p_note text) TO ${approle};

REVOKE ALL ON FUNCTION platform_support_status(p_id uuid, p_status text, p_version bigint) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_support_status(p_id uuid, p_status text, p_version bigint) TO ${approle};

REVOKE ALL ON FUNCTION platform_support_tickets(p_tenant uuid, p_status text, p_limit integer, p_offset integer) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_support_tickets(p_tenant uuid, p_status text, p_limit integer, p_offset integer) TO ${approle};

REVOKE ALL ON FUNCTION platform_usage_history(p_tenant uuid, p_days integer) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_usage_history(p_tenant uuid, p_days integer) TO ${approle};

REVOKE ALL ON FUNCTION platform_usage_page(p_search text, p_alert text, p_limit integer, p_offset integer) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_usage_page(p_search text, p_alert text, p_limit integer, p_offset integer) TO ${approle};

REVOKE ALL ON FUNCTION record_usage_snapshots() FROM PUBLIC;
GRANT ALL ON FUNCTION record_usage_snapshots() TO ${approle};

REVOKE ALL ON FUNCTION require_platform_admin() FROM PUBLIC;

REVOKE ALL ON FUNCTION require_platform_role(p_roles text[]) FROM PUBLIC;

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE feature_definitions TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE organisation_usage_snapshots TO ${approle};

GRANT SELECT ON TABLE platform_settings TO ${approle};

GRANT SELECT,INSERT ON TABLE subscription_plan_versions TO ${approle};
