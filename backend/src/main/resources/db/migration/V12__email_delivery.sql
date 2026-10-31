-- A user's chosen language for email. Empty means the organisation's default.
ALTER TABLE users ADD COLUMN preferred_language TEXT;

-- Which template a notification's email uses, so that its language is chosen when it is sent.
ALTER TABLE notifications ADD COLUMN email_template TEXT;
ALTER TABLE notifications ADD COLUMN email_variables JSONB;

-- One row for each delivery attempt. Bodies are never kept, because they can hold one-time links.
-- Account emails have no tenant, like the audit rows for the same events.
CREATE TABLE email_delivery_logs (
    id UUID PRIMARY KEY,
    tenant_id UUID REFERENCES tenants (id),
    recipient TEXT NOT NULL,
    template TEXT NOT NULL,
    language TEXT NOT NULL,
    status TEXT NOT NULL,
    attempt INT NOT NULL,
    error_type TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_email_delivery_logs_status CHECK (status IN ('SENT', 'FAILED'))
);
CREATE INDEX ix_email_delivery_logs_tenant_time ON email_delivery_logs (tenant_id, created_at DESC);
REVOKE UPDATE, DELETE ON email_delivery_logs FROM ${approle};
ALTER TABLE email_delivery_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE email_delivery_logs FORCE ROW LEVEL SECURITY;
CREATE POLICY email_delivery_logs_read ON email_delivery_logs FOR SELECT USING (tenant_id = current_tenant_id());
CREATE POLICY email_delivery_logs_insert ON email_delivery_logs FOR INSERT
    WITH CHECK (tenant_id IS NULL OR tenant_id = current_tenant_id());
