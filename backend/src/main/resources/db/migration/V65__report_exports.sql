-- A report too long for the screen is exported in the background, as the person who asked for it, and kept for a week.
CREATE TABLE report_exports (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    requested_by uuid NOT NULL,
    report text NOT NULL,
    from_date date NOT NULL,
    to_date date NOT NULL,
    facility_id uuid,
    status text NOT NULL,
    row_count integer,
    truncated boolean NOT NULL DEFAULT false,
    size_bytes bigint,
    storage_key text,
    failure text,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    expires_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT report_exports_pkey PRIMARY KEY (id),
    CONSTRAINT fk_report_exports_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_report_exports_requested_by FOREIGN KEY (requested_by) REFERENCES users (id),
    CONSTRAINT ck_report_exports_status CHECK (status IN ('QUEUED', 'RUNNING', 'READY', 'FAILED', 'EXPIRED')),
    CONSTRAINT ck_report_exports_ready CHECK (status <> 'READY'
        OR (storage_key IS NOT NULL AND row_count IS NOT NULL AND expires_at IS NOT NULL)),
    CONSTRAINT ck_report_exports_period CHECK (from_date <= to_date)
);
CREATE INDEX ix_report_exports_requested_by ON report_exports (tenant_id, requested_by, created_at DESC);
CREATE INDEX ix_report_exports_user ON report_exports (requested_by);
CREATE INDEX ix_report_exports_open ON report_exports (status) WHERE status IN ('QUEUED', 'RUNNING', 'READY');
CREATE TRIGGER trg_updated_at BEFORE UPDATE ON report_exports FOR EACH ROW EXECUTE FUNCTION set_updated_at();
ALTER TABLE report_exports ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY report_exports FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON report_exports
    USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));
-- For the job's function below, which runs as the owner and looks across organisations.
CREATE POLICY report_export_scan ON report_exports FOR SELECT TO "${flyway:user}" USING (true);

-- Organisations with an export left waiting by a restart, cut off mid-run, or past its week.
CREATE FUNCTION tenants_with_report_exports_to_tidy() RETURNS SETOF uuid
    LANGUAGE sql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
    SELECT DISTINCT tenant_id FROM report_exports
    WHERE (status = 'QUEUED' AND created_at < now() - INTERVAL '1 minute')
       OR (status = 'RUNNING' AND started_at < now() - INTERVAL '30 minutes')
       OR (status = 'READY' AND expires_at < now());
$$;
REVOKE ALL ON FUNCTION tenants_with_report_exports_to_tidy() FROM PUBLIC;
GRANT ALL ON FUNCTION tenants_with_report_exports_to_tidy() TO ${approle};
