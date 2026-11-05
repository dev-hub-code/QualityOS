-- Working rules an organisation chooses for itself. One row per organisation, written on its first save.
-- Until then every rule has its default, so organisations that never open the settings need no row.
CREATE TABLE tenant_settings (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    -- While the organisation has a single active member, that person may review their own work.
    -- Each time they do, the reason is required and recorded in the audit log as SELF_REVIEW.
    single_person_review boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT tenant_settings_pkey PRIMARY KEY (id),
    CONSTRAINT uq_tenant_settings_tenant UNIQUE (tenant_id),
    CONSTRAINT tenant_settings_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id)
);

ALTER TABLE tenant_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY tenant_settings FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON tenant_settings
    USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON tenant_settings FOR EACH ROW EXECUTE FUNCTION set_updated_at();
