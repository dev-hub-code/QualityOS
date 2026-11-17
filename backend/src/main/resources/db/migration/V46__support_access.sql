-- An organisation can let ProductionOS support see its records, read-only, for a time it chooses. Nothing is shared
-- until an administrator grants it, it ends by itself, and it can be withdrawn at any moment.
CREATE TABLE support_access_grants (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    granted_by uuid NOT NULL,
    granted_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    note text,
    revoked_at timestamp with time zone,
    revoked_by uuid,
    CONSTRAINT support_access_grants_pkey PRIMARY KEY (id),
    CONSTRAINT fk_support_access_grants_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT ck_support_access_grants_window
        CHECK (expires_at > granted_at AND expires_at <= granted_at + INTERVAL '7 days'),
    CONSTRAINT ck_support_access_grants_note CHECK (note IS NULL OR char_length(note) <= 500)
);
CREATE INDEX idx_support_access_grants_tenant ON support_access_grants (tenant_id, granted_at DESC);

-- What support did while it had access. Kept as written: the application can add rows but never change them.
CREATE TABLE support_access_events (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    grant_id uuid NOT NULL,
    operator_id uuid NOT NULL,
    operator_name text NOT NULL,
    kind text NOT NULL,
    detail text NOT NULL,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT support_access_events_pkey PRIMARY KEY (id),
    CONSTRAINT fk_support_access_events_grant FOREIGN KEY (grant_id) REFERENCES support_access_grants (id),
    CONSTRAINT ck_support_access_events_kind CHECK (kind IN ('STARTED', 'VIEWED'))
);
CREATE INDEX idx_support_access_events_tenant ON support_access_events (tenant_id, occurred_at DESC);

ALTER TABLE support_access_grants ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY support_access_grants FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON support_access_grants
    USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

ALTER TABLE support_access_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY support_access_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON support_access_events
    USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

REVOKE ALL ON TABLE support_access_grants FROM ${approle};
GRANT SELECT, INSERT, UPDATE ON TABLE support_access_grants TO ${approle};
REVOKE ALL ON TABLE support_access_events FROM ${approle};
GRANT SELECT, INSERT ON TABLE support_access_events TO ${approle};
