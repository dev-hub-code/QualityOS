-- An announcement can also be emailed. Each organisation it reaches is emailed once, on or after its start date.
ALTER TABLE platform_announcements ADD COLUMN email boolean DEFAULT false NOT NULL;

-- Which organisations have been emailed each announcement. Written and read only through the functions below.
CREATE TABLE platform_announcement_emails (
    announcement_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    sent_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_announcement_emails_pkey PRIMARY KEY (announcement_id, tenant_id),
    CONSTRAINT fk_announcement_emails_announcement FOREIGN KEY (announcement_id) REFERENCES platform_announcements(id),
    CONSTRAINT fk_announcement_emails_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id)
);
REVOKE ALL ON TABLE platform_announcement_emails FROM ${approle};
ALTER TABLE platform_announcement_emails ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY platform_announcement_emails FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON platform_announcement_emails
    USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));
-- The functions below run as the owner, who scans every organisation to find the ones still to email.
CREATE POLICY announcement_scan ON platform_announcement_emails FOR SELECT TO "${flyway:user}" USING (true);

DROP FUNCTION platform_announcement_save(uuid, text, text, text, text, date, date, boolean, bigint);

-- With no id it creates. Returns nothing when the record has moved on, or was never there.
CREATE FUNCTION platform_announcement_save(p_id uuid, p_title text, p_message text, p_severity text, p_audience text, p_starts date, p_ends date, p_active boolean, p_email boolean, p_version bigint) RETURNS uuid
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
DECLARE
    saved UUID;
BEGIN
    PERFORM require_platform_role(ARRAY['SUPER_ADMIN', 'SUPPORT_ADMIN']);
    IF p_id IS NULL THEN
        INSERT INTO platform_announcements (id, title, message, severity, audience, starts_on, ends_on, active, email)
        VALUES (gen_random_uuid(), p_title, p_message, p_severity, p_audience, p_starts, p_ends, p_active, p_email)
        RETURNING id INTO saved;
    ELSE
        UPDATE platform_announcements
        SET title = p_title, message = p_message, severity = p_severity, audience = p_audience, starts_on = p_starts,
            ends_on = p_ends, active = p_active, email = p_email, version = version + 1, updated_at = now()
        WHERE id = p_id AND version = p_version
        RETURNING id INTO saved;
    END IF;
    RETURN saved;
END;
$$;

REVOKE ALL ON FUNCTION platform_announcement_save(uuid, text, text, text, text, date, date, boolean, boolean, bigint) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_announcement_save(uuid, text, text, text, text, date, date, boolean, boolean, bigint) TO ${approle};

-- Active organisations that an emailed announcement reaches today and that have not been emailed it yet.
-- The audience follows current_announcements(): on trial is TRIAL, any other subscription is PAID.
CREATE FUNCTION tenants_awaiting_announcement_email() RETURNS SETOF uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
    SELECT DISTINCT t.id
    FROM platform_announcements a
    CROSS JOIN tenants t
    LEFT JOIN subscriptions s ON s.tenant_id = t.id
    WHERE a.active AND a.email AND t.status = 'ACTIVE'
      AND (now() AT TIME ZONE 'Asia/Kolkata')::DATE BETWEEN a.starts_on AND a.ends_on
      AND (a.audience = 'ALL' OR a.audience = CASE s.status WHEN 'TRIALING' THEN 'TRIAL' ELSE 'PAID' END)
      AND NOT EXISTS (SELECT 1 FROM platform_announcement_emails e WHERE e.announcement_id = a.id AND e.tenant_id = t.id);
$$;

REVOKE ALL ON FUNCTION tenants_awaiting_announcement_email() FROM PUBLIC;
GRANT ALL ON FUNCTION tenants_awaiting_announcement_email() TO ${approle};

-- The signed-in organisation's announcements to email now, marked as emailed in the same step so each goes once.
-- The caller sends them in the same transaction, so a failure leaves them unmarked for the next run.
CREATE FUNCTION claim_announcement_emails() RETURNS TABLE(id uuid, title text, message text, severity text)
    LANGUAGE sql VOLATILE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
    WITH due AS (
        SELECT c.id, c.title, c.message, c.severity
        FROM current_announcements() c
        JOIN platform_announcements a ON a.id = c.id
        WHERE a.email
    ), marked AS (
        INSERT INTO platform_announcement_emails (announcement_id, tenant_id)
        SELECT due.id, current_tenant_id() FROM due
        ON CONFLICT DO NOTHING
        RETURNING announcement_id
    )
    SELECT due.id, due.title, due.message, due.severity FROM due JOIN marked ON marked.announcement_id = due.id;
$$;

REVOKE ALL ON FUNCTION claim_announcement_emails() FROM PUBLIC;
GRANT ALL ON FUNCTION claim_announcement_emails() TO ${approle};
