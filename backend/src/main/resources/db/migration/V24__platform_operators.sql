-- The Control Center, fourth part: operator roles, re-authentication, and what operators can see of the platform's
-- own running. Every existing administrator becomes a SUPER_ADMIN, so PLATFORM_ADMIN keeps meaning what it meant.

ALTER TABLE platform_admins
    ADD COLUMN role TEXT NOT NULL DEFAULT 'SUPER_ADMIN',
    ADD COLUMN deactivated_at TIMESTAMPTZ,
    ADD COLUMN added_by UUID REFERENCES users (id),
    ADD COLUMN reauthenticated_until TIMESTAMPTZ,
    ADD CONSTRAINT ck_platform_admin_role
        CHECK (role IN ('SUPER_ADMIN', 'BILLING_ADMIN', 'SUPPORT_ADMIN', 'READ_ONLY_AUDITOR'));

-- Links a delivery attempt to what can be sent again. Account emails have no notification, and are never resent.
ALTER TABLE email_delivery_logs ADD COLUMN notification_id UUID;
CREATE INDEX ix_email_delivery_logs_time ON email_delivery_logs (created_at DESC);

ALTER TABLE subscription_invoices ADD COLUMN overdue_notified_at TIMESTAMPTZ;

CREATE INDEX ix_audit_logs_time ON audit_logs (occurred_at DESC);

DO $$
BEGIN
    EXECUTE format('CREATE POLICY platform_scan ON audit_logs FOR SELECT TO %I USING (true)', current_user);
    EXECUTE format('CREATE POLICY platform_scan ON email_delivery_logs FOR SELECT TO %I USING (true)', current_user);
END;
$$;

CREATE OR REPLACE FUNCTION require_platform_admin() RETURNS void
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM platform_admins
                   WHERE user_id = current_app_user_id() AND deactivated_at IS NULL) THEN
        RAISE EXCEPTION 'platform administrator required' USING ERRCODE = 'insufficient_privilege';
    END IF;
END;
$$;

CREATE FUNCTION require_platform_role(p_roles TEXT[]) RETURNS void
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM platform_admins
                   WHERE user_id = current_app_user_id() AND deactivated_at IS NULL AND role = ANY (p_roles)) THEN
        RAISE EXCEPTION 'platform role required' USING ERRCODE = 'insufficient_privilege';
    END IF;
END;
$$;
REVOKE ALL ON FUNCTION require_platform_role(TEXT[]) FROM PUBLIC;

-- Call only once the password has been checked.
CREATE FUNCTION platform_mark_reauthenticated(p_minutes INT) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS
$$
BEGIN
    PERFORM require_platform_admin();
    UPDATE platform_admins SET reauthenticated_until = now() + make_interval(mins => p_minutes)
    WHERE user_id = current_app_user_id();
END;
$$;
REVOKE ALL ON FUNCTION platform_mark_reauthenticated(INT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_mark_reauthenticated(INT) TO ${approle};

CREATE FUNCTION platform_recently_reauthenticated() RETURNS BOOLEAN
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS
$$
    SELECT EXISTS (SELECT 1 FROM platform_admins
                   WHERE user_id = current_app_user_id() AND deactivated_at IS NULL
                     AND reauthenticated_until > now());
$$;
REVOKE ALL ON FUNCTION platform_recently_reauthenticated() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_recently_reauthenticated() TO ${approle};

-- The application cannot write platform_admins, so a mistake in it cannot grant access. These functions can, and each
-- insists on an active SUPER_ADMIN at the database. Each returns how it went, for the application to report.
CREATE FUNCTION platform_operator_add(p_email TEXT, p_role TEXT) RETURNS TEXT
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS
$$
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
REVOKE ALL ON FUNCTION platform_operator_add(TEXT, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_operator_add(TEXT, TEXT) TO ${approle};

-- Locks every active SUPER_ADMIN, so that two of them removing each other at once cannot leave none.
CREATE FUNCTION platform_operator_change(p_user UUID, p_role TEXT, p_active BOOLEAN) RETURNS TEXT
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS
$$
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
REVOKE ALL ON FUNCTION platform_operator_change(UUID, TEXT, BOOLEAN) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_operator_change(UUID, TEXT, BOOLEAN) TO ${approle};

CREATE FUNCTION platform_operators()
    RETURNS TABLE (user_id UUID, email TEXT, full_name TEXT, role TEXT, added_at TIMESTAMPTZ,
                   deactivated_at TIMESTAMPTZ, added_by TEXT, last_activity TIMESTAMPTZ)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS
$$
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
REVOKE ALL ON FUNCTION platform_operators() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_operators() TO ${approle};

-- Only what operators did to the platform or its customers: not their own work in their own organisation, and not
-- anyone else's trail. An action taken on an organisation is stored as PLATFORM_ACTION, with its name inside.
CREATE FUNCTION platform_audit_page(
    p_search TEXT, p_action TEXT, p_entity TEXT, p_from TIMESTAMPTZ, p_to TIMESTAMPTZ, p_limit INT, p_offset INT)
    RETURNS TABLE (id UUID, occurred_at TIMESTAMPTZ, actor TEXT, action TEXT, entity_type TEXT, entity_id TEXT,
                   organisation TEXT, previous_state TEXT, new_state TEXT, reason TEXT, correlation_id TEXT,
                   ip_address TEXT, total BIGINT)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS
$$
#variable_conflict use_column
DECLARE
    pattern TEXT := '%' || replace(replace(replace(COALESCE(p_search, ''), '\', '\\'), '%', '\%'), '_', '\_') || '%';
BEGIN
    PERFORM require_platform_role(ARRAY['SUPER_ADMIN', 'READ_ONLY_AUDITOR']);
    RETURN QUERY
    SELECT a.id, a.occurred_at, u.email, COALESCE(a.new_state ->> 'action', a.action), a.entity_type, a.entity_id,
           CASE WHEN a.entity_type IN ('SubscriptionPlan', 'FeatureDefinition', 'PlatformOperator', 'PlatformAnalytics')
                THEN 'Platform' ELSE t.name END,
           a.previous_state::TEXT, a.new_state::TEXT, a.new_state ->> 'reason', a.correlation_id, a.ip_address,
           count(*) OVER ()
    FROM audit_logs a
    JOIN platform_admins o ON o.user_id = a.user_id
    LEFT JOIN users u ON u.id = a.user_id
    LEFT JOIN tenants t ON t.id = a.tenant_id
    WHERE a.entity_type = ANY (ARRAY['SubscriptionPlan', 'FeatureDefinition', 'PlatformOperator', 'PlatformAnalytics',
                                     'Organisation', 'Subscription', 'SubscriptionInvoice', 'PaymentTransaction',
                                     'Notification', 'Organization'])
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
REVOKE ALL ON FUNCTION platform_audit_page(TEXT, TEXT, TEXT, TIMESTAMPTZ, TIMESTAMPTZ, INT, INT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_audit_page(TEXT, TEXT, TEXT, TIMESTAMPTZ, TIMESTAMPTZ, INT, INT) TO ${approle};

-- One row per email, however many attempts it took. Only a notification that gave up can be sent again.
CREATE FUNCTION platform_email_page(p_search TEXT, p_status TEXT, p_retryable BOOLEAN, p_limit INT, p_offset INT)
    RETURNS TABLE (id UUID, tenant_id UUID, organisation TEXT, notification_id UUID, recipient TEXT, template TEXT,
                   status TEXT, attempts BIGINT, last_attempt TIMESTAMPTZ, error_type TEXT, retryable BOOLEAN,
                   total BIGINT)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS
$$
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
REVOKE ALL ON FUNCTION platform_email_page(TEXT, TEXT, BOOLEAN, INT, INT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_email_page(TEXT, TEXT, BOOLEAN, INT, INT) TO ${approle};

-- Billing notices only. Quality notifications stay with the organisation.
CREATE FUNCTION platform_notification_page(p_type TEXT, p_limit INT, p_offset INT)
    RETURNS TABLE (id UUID, sent_at TIMESTAMPTZ, type TEXT, title TEXT, organisation TEXT, tenant_id UUID,
                   recipient TEXT, email_status TEXT, total BIGINT)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS
$$
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
REVOKE ALL ON FUNCTION platform_notification_page(TEXT, INT, INT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_notification_page(TEXT, INT, INT) TO ${approle};

-- Ids only, for the notice job: reminders about the subscription, and invoices past due that have not been chased.
CREATE OR REPLACE FUNCTION tenants_with_subscriptions_ending() RETURNS SETOF UUID
    LANGUAGE sql SECURITY DEFINER SET search_path = public AS
$$
    SELECT s.tenant_id FROM subscriptions s
    JOIN subscription_plans p ON p.id = s.plan_id
    WHERE ((s.current_period_ends_at < now() + INTERVAL '7 days'
            AND COALESCE(s.last_notice_days, 8) > -p.grace_days)
           OR EXISTS (SELECT 1 FROM subscription_invoices i
                      WHERE i.tenant_id = s.tenant_id AND i.status = 'ISSUED'
                        AND i.overdue_notified_at IS NULL AND i.due_on < current_date));
$$;
