-- The Control Center, last part: settings, announcements and the support console.
-- Secrets stay in the environment. Nothing here holds one.

CREATE TABLE platform_settings (
    id INT PRIMARY KEY DEFAULT 1,
    default_trial_days INT NOT NULL DEFAULT 14,
    default_grace_days INT NOT NULL DEFAULT 7,
    maintenance_mode BOOLEAN NOT NULL DEFAULT FALSE,
    registration_open BOOLEAN NOT NULL DEFAULT TRUE,
    support_email TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_platform_settings_single CHECK (id = 1),
    CONSTRAINT ck_platform_settings_trial CHECK (default_trial_days BETWEEN 0 AND 365),
    CONSTRAINT ck_platform_settings_grace CHECK (default_grace_days BETWEEN 0 AND 90)
);
INSERT INTO platform_settings DEFAULT VALUES;
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON platform_settings FROM ${approle};
GRANT SELECT ON platform_settings TO ${approle};

CREATE TABLE platform_announcements (
    id UUID PRIMARY KEY,
    title TEXT NOT NULL,
    message TEXT NOT NULL,
    severity TEXT NOT NULL,
    audience TEXT NOT NULL,
    starts_on DATE NOT NULL,
    ends_on DATE NOT NULL,
    active BOOLEAN NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_announcement_severity CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    CONSTRAINT ck_announcement_audience CHECK (audience IN ('ALL', 'TRIAL', 'PAID')),
    CONSTRAINT ck_announcement_dates CHECK (ends_on >= starts_on)
);
REVOKE ALL ON platform_announcements FROM ${approle};

-- Private to operators. The application role has no rights on either table, so no tenant query can reach a note.
CREATE TABLE support_tickets (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    subject TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'OPEN',
    opened_by UUID NOT NULL REFERENCES users (id),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_support_ticket_status
        CHECK (status IN ('OPEN', 'IN_PROGRESS', 'WAITING_FOR_CUSTOMER', 'RESOLVED', 'CLOSED'))
);
CREATE INDEX ix_support_tickets_tenant ON support_tickets (tenant_id, updated_at DESC);
CREATE INDEX ix_support_tickets_status ON support_tickets (status, updated_at DESC);

CREATE TABLE support_notes (
    id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL REFERENCES support_tickets (id),
    author_id UUID NOT NULL REFERENCES users (id),
    body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_support_notes_ticket ON support_notes (ticket_id, created_at);
REVOKE ALL ON support_tickets, support_notes FROM ${approle};
ALTER TABLE support_tickets ENABLE ROW LEVEL SECURITY;
ALTER TABLE support_tickets FORCE ROW LEVEL SECURITY;
ALTER TABLE support_notes ENABLE ROW LEVEL SECURITY;
ALTER TABLE support_notes FORCE ROW LEVEL SECURITY;
DO $$
BEGIN
    EXECUTE format('CREATE POLICY platform_only ON support_tickets TO %I USING (true) WITH CHECK (true)', current_user);
    EXECUTE format('CREATE POLICY platform_only ON support_notes TO %I USING (true) WITH CHECK (true)', current_user);
END;
$$;

CREATE FUNCTION platform_settings_save(
    p_trial INT, p_grace INT, p_maintenance BOOLEAN, p_registration BOOLEAN, p_support TEXT, p_version BIGINT)
    RETURNS BOOLEAN
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS
$$
BEGIN
    PERFORM require_platform_role(ARRAY['SUPER_ADMIN']);
    UPDATE platform_settings
    SET default_trial_days = p_trial, default_grace_days = p_grace, maintenance_mode = p_maintenance,
        registration_open = p_registration, support_email = p_support, version = version + 1, updated_at = now()
    WHERE version = p_version;
    RETURN FOUND;
END;
$$;
REVOKE ALL ON FUNCTION platform_settings_save(INT, INT, BOOLEAN, BOOLEAN, TEXT, BIGINT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_settings_save(INT, INT, BOOLEAN, BOOLEAN, TEXT, BIGINT) TO ${approle};

-- With no id it creates. Returns nothing when the record has moved on, or was never there.
CREATE FUNCTION platform_announcement_save(
    p_id UUID, p_title TEXT, p_message TEXT, p_severity TEXT, p_audience TEXT, p_starts DATE, p_ends DATE,
    p_active BOOLEAN, p_version BIGINT) RETURNS UUID
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS
$$
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
REVOKE ALL ON FUNCTION platform_announcement_save(UUID, TEXT, TEXT, TEXT, TEXT, DATE, DATE, BOOLEAN, BIGINT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_announcement_save(UUID, TEXT, TEXT, TEXT, TEXT, DATE, DATE, BOOLEAN, BIGINT) TO ${approle};

CREATE FUNCTION platform_announcement_list() RETURNS SETOF platform_announcements
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS
$$
BEGIN
    PERFORM require_platform_admin();
    RETURN QUERY SELECT * FROM platform_announcements ORDER BY ends_on DESC, created_at DESC;
END;
$$;
REVOKE ALL ON FUNCTION platform_announcement_list() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_announcement_list() TO ${approle};

-- What the signed-in organisation should see today. Dates are Indian Standard Time, like the rest of the platform.
CREATE FUNCTION current_announcements()
    RETURNS TABLE (id UUID, title TEXT, message TEXT, severity TEXT)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS
$$
    SELECT a.id, a.title, a.message, a.severity
    FROM platform_announcements a
    WHERE a.active
      AND (now() AT TIME ZONE 'Asia/Kolkata')::DATE BETWEEN a.starts_on AND a.ends_on
      AND (a.audience = 'ALL' OR a.audience = (
              SELECT CASE s.status WHEN 'TRIALING' THEN 'TRIAL' ELSE 'PAID' END
              FROM subscriptions s WHERE s.tenant_id = current_tenant_id()))
    ORDER BY CASE a.severity WHEN 'CRITICAL' THEN 0 WHEN 'WARNING' THEN 1 ELSE 2 END, a.starts_on DESC;
$$;
REVOKE ALL ON FUNCTION current_announcements() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION current_announcements() TO ${approle};

CREATE FUNCTION platform_support_open(p_tenant UUID, p_subject TEXT, p_note TEXT) RETURNS UUID
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS
$$
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
REVOKE ALL ON FUNCTION platform_support_open(UUID, TEXT, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_support_open(UUID, TEXT, TEXT) TO ${approle};

-- A closed ticket stays closed, so what it says cannot change afterwards.
CREATE FUNCTION platform_support_status(p_id UUID, p_status TEXT, p_version BIGINT) RETURNS TEXT
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS
$$
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
REVOKE ALL ON FUNCTION platform_support_status(UUID, TEXT, BIGINT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_support_status(UUID, TEXT, BIGINT) TO ${approle};

CREATE FUNCTION platform_support_note(p_id UUID, p_body TEXT) RETURNS TEXT
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS
$$
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
REVOKE ALL ON FUNCTION platform_support_note(UUID, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_support_note(UUID, TEXT) TO ${approle};

CREATE FUNCTION platform_support_tickets(p_tenant UUID, p_status TEXT, p_limit INT, p_offset INT)
    RETURNS TABLE (id UUID, tenant_id UUID, organisation TEXT, subject TEXT, status TEXT, opened_by TEXT,
                   created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ, version BIGINT, total BIGINT)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS
$$
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
REVOKE ALL ON FUNCTION platform_support_tickets(UUID, TEXT, INT, INT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_support_tickets(UUID, TEXT, INT, INT) TO ${approle};

CREATE FUNCTION platform_support_notes(p_id UUID)
    RETURNS TABLE (id UUID, author TEXT, body TEXT, created_at TIMESTAMPTZ)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS
$$
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
REVOKE ALL ON FUNCTION platform_support_notes(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_support_notes(UUID) TO ${approle};

-- Failed emails are the only errors that can be tied to one organisation safely. They carry no record content.
CREATE FUNCTION platform_support_errors(p_tenant UUID)
    RETURNS TABLE (occurred_at TIMESTAMPTZ, template TEXT, error_type TEXT)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS
$$
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
REVOKE ALL ON FUNCTION platform_support_errors(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_support_errors(UUID) TO ${approle};

CREATE OR REPLACE FUNCTION platform_audit_page(
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
