-- An NCR with the same root cause as an existing CAPA is linked to that CAPA instead of needing a duplicate one.
-- A CAPA raised from an NCR keeps its own ncr_id; these are the further NCRs it also answers.
CREATE TABLE ncr_capa_links (
    tenant_id uuid NOT NULL,
    ncr_id uuid NOT NULL,
    capa_id uuid NOT NULL,
    note text NOT NULL,
    linked_by uuid,
    linked_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ncr_capa_links_pkey PRIMARY KEY (tenant_id, ncr_id, capa_id),
    CONSTRAINT ck_ncr_capa_links_note CHECK (length(btrim(note)) > 0),
    CONSTRAINT fk_ncr_capa_links_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_ncr_capa_links_ncr FOREIGN KEY (tenant_id, ncr_id) REFERENCES non_conformances (tenant_id, id),
    CONSTRAINT fk_ncr_capa_links_capa FOREIGN KEY (tenant_id, capa_id) REFERENCES corrective_actions (tenant_id, id)
);

CREATE INDEX ix_ncr_capa_links_capa ON ncr_capa_links (tenant_id, capa_id);

ALTER TABLE ncr_capa_links ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY ncr_capa_links FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ncr_capa_links
    USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));
