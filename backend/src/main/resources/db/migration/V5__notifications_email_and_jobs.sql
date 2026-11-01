-- Notifications, account tokens, email delivery and scheduled job records.
-- ${approle} is the unprivileged role the application connects as.

SET LOCAL check_function_bodies = off;

CREATE FUNCTION tenants_with_pending_email() RETURNS SETOF uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
    SELECT DISTINCT tenant_id FROM notifications WHERE email_status = 'PENDING';
$$;

-- Holds only hashes. Like refresh tokens, these belong to a user rather than to a tenant.
CREATE TABLE account_tokens (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    purpose text NOT NULL,
    token_hash text NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    used_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_account_tokens_purpose CHECK ((purpose = ANY (ARRAY['PASSWORD_RESET'::text, 'EMAIL_VERIFICATION'::text])))
);

-- One row for each delivery attempt. Bodies are never kept, because they can hold one-time links.
-- Account emails have no tenant, like the audit rows for the same events.
CREATE TABLE email_delivery_logs (
    id uuid NOT NULL,
    tenant_id uuid,
    recipient text NOT NULL,
    template text NOT NULL,
    language text NOT NULL,
    status text NOT NULL,
    attempt integer NOT NULL,
    error_type text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    notification_id uuid,
    CONSTRAINT ck_email_delivery_logs_status CHECK ((status = ANY (ARRAY['SENT'::text, 'FAILED'::text])))
);

ALTER TABLE ONLY email_delivery_logs FORCE ROW LEVEL SECURITY;

CREATE TABLE invitations (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    email text NOT NULL,
    role_ids uuid[] NOT NULL,
    token_hash text NOT NULL,
    status text DEFAULT 'PENDING'::text NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    accepted_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_invitations_email_lowercase CHECK ((email = lower(email))),
    CONSTRAINT ck_invitations_status CHECK ((status = ANY (ARRAY['PENDING'::text, 'ACCEPTED'::text, 'REVOKED'::text])))
);

ALTER TABLE ONLY invitations FORCE ROW LEVEL SECURITY;

CREATE TABLE notification_preferences (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    type text NOT NULL,
    email_enabled boolean NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL
);

ALTER TABLE ONLY notification_preferences FORCE ROW LEVEL SECURITY;

CREATE TABLE notifications (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    type text NOT NULL,
    title text NOT NULL,
    body text NOT NULL,
    link text,
    read_at timestamp with time zone,
    email_status text NOT NULL,
    email_attempts integer DEFAULT 0 NOT NULL,
    email_next_attempt_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    email_template text,
    email_variables jsonb,
    CONSTRAINT ck_notifications_email CHECK ((email_status = ANY (ARRAY['NONE'::text, 'PENDING'::text, 'SENT'::text, 'SKIPPED'::text, 'FAILED'::text])))
);

ALTER TABLE ONLY notifications FORCE ROW LEVEL SECURITY;

CREATE TABLE scheduled_job_executions (
    id uuid NOT NULL,
    job_name text NOT NULL,
    instance text NOT NULL,
    started_at timestamp with time zone NOT NULL,
    finished_at timestamp with time zone NOT NULL,
    duration_ms bigint NOT NULL,
    tenants_processed integer NOT NULL,
    records_processed integer NOT NULL,
    notifications_generated integer NOT NULL,
    records_skipped integer NOT NULL,
    failure_count integer NOT NULL,
    status text NOT NULL,
    CONSTRAINT ck_job_executions_status CHECK ((status = ANY (ARRAY['SUCCESS'::text, 'PARTIAL_SUCCESS'::text, 'FAILED'::text])))
);

-- These two describe the platform's own jobs and hold no tenant's data, only counts, so they have no tenant column.
CREATE TABLE scheduled_job_locks (
    job_name text NOT NULL,
    locked_until timestamp with time zone NOT NULL,
    locked_at timestamp with time zone NOT NULL,
    locked_by text NOT NULL
);

ALTER TABLE ONLY account_tokens
    ADD CONSTRAINT account_tokens_pkey PRIMARY KEY (id);

ALTER TABLE ONLY email_delivery_logs
    ADD CONSTRAINT email_delivery_logs_pkey PRIMARY KEY (id);

ALTER TABLE ONLY invitations
    ADD CONSTRAINT invitations_pkey PRIMARY KEY (id);

ALTER TABLE ONLY notification_preferences
    ADD CONSTRAINT notification_preferences_pkey PRIMARY KEY (id);

ALTER TABLE ONLY notifications
    ADD CONSTRAINT notifications_pkey PRIMARY KEY (id);

ALTER TABLE ONLY scheduled_job_executions
    ADD CONSTRAINT scheduled_job_executions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY scheduled_job_locks
    ADD CONSTRAINT scheduled_job_locks_pkey PRIMARY KEY (job_name);

ALTER TABLE ONLY account_tokens
    ADD CONSTRAINT uq_account_tokens_hash UNIQUE (token_hash);

ALTER TABLE ONLY invitations
    ADD CONSTRAINT uq_invitations_hash UNIQUE (token_hash);

ALTER TABLE ONLY invitations
    ADD CONSTRAINT uq_invitations_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY notification_preferences
    ADD CONSTRAINT uq_notification_preferences UNIQUE (tenant_id, user_id, type);

ALTER TABLE ONLY notifications
    ADD CONSTRAINT uq_notifications_tenant_id UNIQUE (tenant_id, id);

CREATE INDEX ix_account_tokens_expiry ON account_tokens USING btree (expires_at);

CREATE INDEX ix_account_tokens_user ON account_tokens USING btree (user_id, purpose);

CREATE INDEX ix_email_delivery_logs_tenant_time ON email_delivery_logs USING btree (tenant_id, created_at DESC);

CREATE INDEX ix_email_delivery_logs_time ON email_delivery_logs USING btree (created_at DESC);

CREATE INDEX ix_job_executions_latest ON scheduled_job_executions USING btree (job_name, started_at DESC);

CREATE INDEX ix_notifications_inbox ON notifications USING btree (tenant_id, user_id, created_at DESC);

CREATE INDEX ix_notifications_pending_email ON notifications USING btree (tenant_id) WHERE (email_status = 'PENDING'::text);

CREATE INDEX ix_notifications_unread ON notifications USING btree (tenant_id, user_id) WHERE (read_at IS NULL);

CREATE UNIQUE INDEX uq_invitations_pending ON invitations USING btree (tenant_id, email) WHERE (status = 'PENDING'::text);

CREATE TRIGGER trg_account_tokens_updated_at BEFORE UPDATE ON account_tokens FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON invitations FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON notification_preferences FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON notifications FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE ONLY account_tokens
    ADD CONSTRAINT account_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id);

ALTER TABLE ONLY email_delivery_logs
    ADD CONSTRAINT email_delivery_logs_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY invitations
    ADD CONSTRAINT invitations_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY notification_preferences
    ADD CONSTRAINT notification_preferences_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY notification_preferences
    ADD CONSTRAINT notification_preferences_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id);

ALTER TABLE ONLY notifications
    ADD CONSTRAINT notifications_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY notifications
    ADD CONSTRAINT notifications_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id);

CREATE POLICY delivery_scan ON notifications FOR SELECT TO "${flyway:user}" USING (true);

ALTER TABLE email_delivery_logs ENABLE ROW LEVEL SECURITY;

CREATE POLICY email_delivery_logs_insert ON email_delivery_logs FOR INSERT WITH CHECK (((tenant_id IS NULL) OR (tenant_id = current_tenant_id())));

CREATE POLICY email_delivery_logs_read ON email_delivery_logs FOR SELECT USING ((tenant_id = current_tenant_id()));

ALTER TABLE invitations ENABLE ROW LEVEL SECURITY;

ALTER TABLE notification_preferences ENABLE ROW LEVEL SECURITY;

ALTER TABLE notifications ENABLE ROW LEVEL SECURITY;

CREATE POLICY platform_scan ON email_delivery_logs FOR SELECT TO "${flyway:user}" USING (true);

CREATE POLICY platform_scan ON invitations FOR SELECT TO "${flyway:user}" USING (true);

CREATE POLICY tenant_isolation ON invitations USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON notification_preferences USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON notifications USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

REVOKE ALL ON FUNCTION tenants_with_pending_email() FROM PUBLIC;
GRANT ALL ON FUNCTION tenants_with_pending_email() TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE account_tokens TO ${approle};

GRANT SELECT,INSERT ON TABLE email_delivery_logs TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE invitations TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE notification_preferences TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE notifications TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE scheduled_job_executions TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE scheduled_job_locks TO ${approle};
