-- In-app and email notifications, invitations, and the one-time tokens behind password reset and email verification.

ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMPTZ;

-- Holds only hashes. Like refresh tokens, these belong to a user rather than to a tenant.
CREATE TABLE account_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    purpose TEXT NOT NULL,
    token_hash TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_account_tokens_hash UNIQUE (token_hash),
    CONSTRAINT ck_account_tokens_purpose CHECK (purpose IN ('PASSWORD_RESET', 'EMAIL_VERIFICATION'))
);
CREATE INDEX ix_account_tokens_user ON account_tokens (user_id, purpose);
CREATE INDEX ix_account_tokens_expiry ON account_tokens (expires_at);
CREATE TRIGGER trg_account_tokens_updated_at BEFORE UPDATE ON account_tokens
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE INDEX ix_refresh_tokens_expiry ON refresh_tokens (expires_at);

CREATE TABLE invitations (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    email TEXT NOT NULL,
    role_ids UUID[] NOT NULL,
    token_hash TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    expires_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_invitations_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_invitations_hash UNIQUE (token_hash),
    CONSTRAINT ck_invitations_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED')),
    CONSTRAINT ck_invitations_email_lowercase CHECK (email = lower(email))
);
CREATE UNIQUE INDEX uq_invitations_pending ON invitations (tenant_id, email) WHERE status = 'PENDING';
SELECT apply_tenant_standards('invitations');

CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    user_id UUID NOT NULL REFERENCES users (id),
    type TEXT NOT NULL,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    link TEXT,
    read_at TIMESTAMPTZ,
    email_status TEXT NOT NULL,
    email_attempts INT NOT NULL DEFAULT 0,
    email_next_attempt_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_notifications_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_notifications_email CHECK (email_status IN ('NONE', 'PENDING', 'SENT', 'SKIPPED', 'FAILED'))
);
CREATE INDEX ix_notifications_inbox ON notifications (tenant_id, user_id, created_at DESC);
CREATE INDEX ix_notifications_unread ON notifications (tenant_id, user_id) WHERE read_at IS NULL;
CREATE INDEX ix_notifications_pending_email ON notifications (tenant_id) WHERE email_status = 'PENDING';
SELECT apply_tenant_standards('notifications');

CREATE TABLE notification_preferences (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    user_id UUID NOT NULL REFERENCES users (id),
    type TEXT NOT NULL,
    email_enabled BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_notification_preferences UNIQUE (tenant_id, user_id, type)
);
SELECT apply_tenant_standards('notification_preferences');

-- The one thing the delivery job needs before it has a tenant: which tenants have work. It returns ids only, and
-- each tenant's rows are then read under that tenant's own context. The policy below applies to this schema's
-- owner, never to the application role, and exists only so that this function can see across tenants.
DO $$
BEGIN
    EXECUTE format('CREATE POLICY delivery_scan ON notifications FOR SELECT TO %I USING (true)', current_user);
END;
$$;

CREATE FUNCTION tenants_with_pending_email() RETURNS SETOF UUID
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS
$$
    SELECT DISTINCT tenant_id FROM notifications WHERE email_status = 'PENDING';
$$;
REVOKE ALL ON FUNCTION tenants_with_pending_email() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenants_with_pending_email() TO ${approle};
