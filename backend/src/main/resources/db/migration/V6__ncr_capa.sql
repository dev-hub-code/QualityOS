-- Non-conformances, corrective actions and their history.
-- ${approle} is the unprivileged role the application connects as.

SET LOCAL check_function_bodies = off;

CREATE FUNCTION protect_finished_record() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.status IN ('CLOSED', 'REJECTED', 'CANCELLED') THEN
        RAISE EXCEPTION '% % cannot be changed', TG_TABLE_NAME, OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE FUNCTION tenants_with_overdue_capas() RETURNS SETOF uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
    SELECT DISTINCT tenant_id FROM corrective_actions
    WHERE status NOT IN ('CLOSED', 'CANCELLED') AND target_date < now()::date + 1;
$$;

CREATE TABLE capa_actions (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    capa_id uuid NOT NULL,
    action_type text NOT NULL,
    description text NOT NULL,
    owner_id uuid NOT NULL,
    target_date date NOT NULL,
    completed_at timestamp with time zone,
    completion_note text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_capa_action_done CHECK (((completed_at IS NULL) = (completion_note IS NULL))),
    CONSTRAINT ck_capa_action_type CHECK ((action_type = ANY (ARRAY['CORRECTIVE'::text, 'PREVENTIVE'::text])))
);

ALTER TABLE ONLY capa_actions FORCE ROW LEVEL SECURITY;

CREATE TABLE capa_status_history (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    capa_id uuid NOT NULL,
    from_status text,
    to_status text NOT NULL,
    note text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL
);

ALTER TABLE ONLY capa_status_history FORCE ROW LEVEL SECURITY;

CREATE TABLE capa_verifications (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    capa_id uuid NOT NULL,
    effective boolean NOT NULL,
    method text NOT NULL,
    note text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL
);

ALTER TABLE ONLY capa_verifications FORCE ROW LEVEL SECURITY;

CREATE TABLE corrective_actions (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    capa_number text NOT NULL,
    status text DEFAULT 'OPEN'::text NOT NULL,
    ncr_id uuid,
    title text NOT NULL,
    problem_statement text NOT NULL,
    root_cause text,
    five_whys jsonb DEFAULT '[]'::jsonb NOT NULL,
    fishbone jsonb DEFAULT '{}'::jsonb NOT NULL,
    owner_id uuid NOT NULL,
    target_date date NOT NULL,
    effectiveness_required boolean DEFAULT true NOT NULL,
    overdue_notified_at timestamp with time zone,
    outcome_note text,
    closed_at timestamp with time zone,
    closed_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    due_reminded_at timestamp with time zone,
    facility_id uuid,
    CONSTRAINT ck_capa_closed CHECK (((status <> 'CLOSED'::text) OR ((closed_at IS NOT NULL) AND (closed_by IS NOT NULL) AND (root_cause IS NOT NULL)))),
    CONSTRAINT ck_capa_status CHECK ((status = ANY (ARRAY['OPEN'::text, 'ROOT_CAUSE_ANALYSIS'::text, 'ACTION_PLANNING'::text, 'IMPLEMENTATION'::text, 'EFFECTIVENESS_VERIFICATION'::text, 'CLOSED'::text, 'CANCELLED'::text])))
);

ALTER TABLE ONLY corrective_actions FORCE ROW LEVEL SECURITY;

CREATE TABLE defect_categories (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    code text NOT NULL,
    name text NOT NULL,
    archived_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL
);

ALTER TABLE ONLY defect_categories FORCE ROW LEVEL SECURITY;

CREATE TABLE ncr_status_history (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    ncr_id uuid NOT NULL,
    from_status text,
    to_status text NOT NULL,
    note text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL
);

ALTER TABLE ONLY ncr_status_history FORCE ROW LEVEL SECURITY;

CREATE TABLE non_conformances (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    ncr_number text NOT NULL,
    status text DEFAULT 'OPEN'::text NOT NULL,
    source text NOT NULL,
    inspection_id uuid,
    supplier_id uuid,
    material_id uuid,
    batch_number text,
    defect_category_id uuid,
    severity text NOT NULL,
    title text NOT NULL,
    description text NOT NULL,
    rejected_quantity numeric(18,6),
    assigned_to uuid,
    containment_action text,
    root_cause text,
    verification_requested_by uuid,
    outcome_note text,
    closed_at timestamp with time zone,
    closed_by uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    audit_id uuid,
    audit_finding_id uuid,
    facility_id uuid,
    CONSTRAINT ck_ncr_audit_source CHECK (((source = 'AUDIT'::text) = (audit_finding_id IS NOT NULL))),
    CONSTRAINT ck_ncr_closed CHECK (((status <> 'CLOSED'::text) OR ((closed_at IS NOT NULL) AND (closed_by IS NOT NULL) AND (root_cause IS NOT NULL) AND (containment_action IS NOT NULL)))),
    CONSTRAINT ck_ncr_inspection_source CHECK (((source = 'INSPECTION'::text) = (inspection_id IS NOT NULL))),
    CONSTRAINT ck_ncr_quantity CHECK (((rejected_quantity IS NULL) OR (rejected_quantity >= (0)::numeric))),
    CONSTRAINT ck_ncr_severity CHECK ((severity = ANY (ARRAY['CRITICAL'::text, 'MAJOR'::text, 'MINOR'::text]))),
    CONSTRAINT ck_ncr_source CHECK ((source = ANY (ARRAY['INSPECTION'::text, 'MANUAL'::text, 'AUDIT'::text]))),
    CONSTRAINT ck_ncr_status CHECK ((status = ANY (ARRAY['OPEN'::text, 'UNDER_INVESTIGATION'::text, 'CONTAINMENT'::text, 'ACTION_REQUIRED'::text, 'PENDING_VERIFICATION'::text, 'CLOSED'::text, 'REJECTED'::text, 'CANCELLED'::text])))
);

ALTER TABLE ONLY non_conformances FORCE ROW LEVEL SECURITY;

ALTER TABLE ONLY capa_actions
    ADD CONSTRAINT capa_actions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY capa_status_history
    ADD CONSTRAINT capa_status_history_pkey PRIMARY KEY (id);

ALTER TABLE ONLY capa_verifications
    ADD CONSTRAINT capa_verifications_pkey PRIMARY KEY (id);

ALTER TABLE ONLY corrective_actions
    ADD CONSTRAINT corrective_actions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY defect_categories
    ADD CONSTRAINT defect_categories_pkey PRIMARY KEY (id);

ALTER TABLE ONLY ncr_status_history
    ADD CONSTRAINT ncr_status_history_pkey PRIMARY KEY (id);

ALTER TABLE ONLY non_conformances
    ADD CONSTRAINT non_conformances_pkey PRIMARY KEY (id);

ALTER TABLE ONLY capa_actions
    ADD CONSTRAINT uq_capa_actions_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY corrective_actions
    ADD CONSTRAINT uq_corrective_actions_number UNIQUE (tenant_id, capa_number);

ALTER TABLE ONLY corrective_actions
    ADD CONSTRAINT uq_corrective_actions_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY defect_categories
    ADD CONSTRAINT uq_defect_categories_code UNIQUE (tenant_id, code);

ALTER TABLE ONLY defect_categories
    ADD CONSTRAINT uq_defect_categories_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY non_conformances
    ADD CONSTRAINT uq_non_conformances_number UNIQUE (tenant_id, ncr_number);

ALTER TABLE ONLY non_conformances
    ADD CONSTRAINT uq_non_conformances_tenant_id UNIQUE (tenant_id, id);

CREATE INDEX idx_capas_facility ON corrective_actions USING btree (tenant_id, facility_id);

CREATE INDEX idx_ncrs_facility ON non_conformances USING btree (tenant_id, facility_id);

CREATE INDEX ix_capa_actions ON capa_actions USING btree (tenant_id, capa_id);

CREATE INDEX ix_capa_closed ON corrective_actions USING btree (tenant_id, closed_at) WHERE (closed_at IS NOT NULL);

CREATE INDEX ix_capa_created ON corrective_actions USING btree (tenant_id, created_at);

CREATE INDEX ix_capa_history ON capa_status_history USING btree (tenant_id, capa_id, created_at);

CREATE INDEX ix_capa_ncr ON corrective_actions USING btree (tenant_id, ncr_id);

CREATE INDEX ix_capa_overdue ON corrective_actions USING btree (target_date) WHERE (status <> ALL (ARRAY['CLOSED'::text, 'CANCELLED'::text]));

CREATE INDEX ix_capa_status ON corrective_actions USING btree (tenant_id, status);

CREATE INDEX ix_capa_verifications ON capa_verifications USING btree (tenant_id, capa_id, created_at);

CREATE INDEX ix_ncr_closed ON non_conformances USING btree (tenant_id, closed_at) WHERE (closed_at IS NOT NULL);

CREATE INDEX ix_ncr_created ON non_conformances USING btree (tenant_id, created_at);

CREATE INDEX ix_ncr_history ON ncr_status_history USING btree (tenant_id, ncr_id, created_at);

CREATE INDEX ix_ncr_status ON non_conformances USING btree (tenant_id, status);

CREATE UNIQUE INDEX uq_ncr_audit_finding ON non_conformances USING btree (tenant_id, audit_finding_id) WHERE (audit_finding_id IS NOT NULL);

CREATE UNIQUE INDEX uq_ncr_per_inspection ON non_conformances USING btree (tenant_id, inspection_id) WHERE (inspection_id IS NOT NULL);

CREATE TRIGGER trg_append_only BEFORE DELETE OR UPDATE ON capa_status_history FOR EACH ROW EXECUTE FUNCTION reject_change();

CREATE TRIGGER trg_append_only BEFORE DELETE OR UPDATE ON capa_verifications FOR EACH ROW EXECUTE FUNCTION reject_change();

CREATE TRIGGER trg_append_only BEFORE DELETE OR UPDATE ON ncr_status_history FOR EACH ROW EXECUTE FUNCTION reject_change();

CREATE TRIGGER trg_protect_finished BEFORE DELETE OR UPDATE ON corrective_actions FOR EACH ROW EXECUTE FUNCTION protect_finished_record();

CREATE TRIGGER trg_protect_finished BEFORE DELETE OR UPDATE ON non_conformances FOR EACH ROW EXECUTE FUNCTION protect_finished_record();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON capa_actions FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON capa_status_history FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON capa_verifications FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON corrective_actions FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON defect_categories FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON ncr_status_history FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON non_conformances FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE ONLY capa_actions
    ADD CONSTRAINT capa_actions_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES users(id);

ALTER TABLE ONLY capa_actions
    ADD CONSTRAINT capa_actions_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY capa_status_history
    ADD CONSTRAINT capa_status_history_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY capa_verifications
    ADD CONSTRAINT capa_verifications_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY corrective_actions
    ADD CONSTRAINT corrective_actions_closed_by_fkey FOREIGN KEY (closed_by) REFERENCES users(id);

ALTER TABLE ONLY corrective_actions
    ADD CONSTRAINT corrective_actions_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES users(id);

ALTER TABLE ONLY corrective_actions
    ADD CONSTRAINT corrective_actions_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY defect_categories
    ADD CONSTRAINT defect_categories_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY capa_actions
    ADD CONSTRAINT fk_capa_action FOREIGN KEY (tenant_id, capa_id) REFERENCES corrective_actions(tenant_id, id);

ALTER TABLE ONLY capa_status_history
    ADD CONSTRAINT fk_capa_history FOREIGN KEY (tenant_id, capa_id) REFERENCES corrective_actions(tenant_id, id);

ALTER TABLE ONLY corrective_actions
    ADD CONSTRAINT fk_capa_ncr FOREIGN KEY (tenant_id, ncr_id) REFERENCES non_conformances(tenant_id, id);

ALTER TABLE ONLY capa_verifications
    ADD CONSTRAINT fk_capa_verification FOREIGN KEY (tenant_id, capa_id) REFERENCES corrective_actions(tenant_id, id);

ALTER TABLE ONLY corrective_actions
    ADD CONSTRAINT fk_capas_facility FOREIGN KEY (tenant_id, facility_id) REFERENCES facilities(tenant_id, id);

ALTER TABLE ONLY non_conformances
    ADD CONSTRAINT fk_ncr_defect FOREIGN KEY (tenant_id, defect_category_id) REFERENCES defect_categories(tenant_id, id);

ALTER TABLE ONLY ncr_status_history
    ADD CONSTRAINT fk_ncr_history FOREIGN KEY (tenant_id, ncr_id) REFERENCES non_conformances(tenant_id, id);

ALTER TABLE ONLY non_conformances
    ADD CONSTRAINT fk_ncr_inspection FOREIGN KEY (tenant_id, inspection_id) REFERENCES inspections(tenant_id, id);

ALTER TABLE ONLY non_conformances
    ADD CONSTRAINT fk_ncr_material FOREIGN KEY (tenant_id, material_id) REFERENCES materials(tenant_id, id);

ALTER TABLE ONLY non_conformances
    ADD CONSTRAINT fk_ncr_supplier FOREIGN KEY (tenant_id, supplier_id) REFERENCES suppliers(tenant_id, id);

ALTER TABLE ONLY non_conformances
    ADD CONSTRAINT fk_ncrs_facility FOREIGN KEY (tenant_id, facility_id) REFERENCES facilities(tenant_id, id);

ALTER TABLE ONLY ncr_status_history
    ADD CONSTRAINT ncr_status_history_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY non_conformances
    ADD CONSTRAINT non_conformances_assigned_to_fkey FOREIGN KEY (assigned_to) REFERENCES users(id);

ALTER TABLE ONLY non_conformances
    ADD CONSTRAINT non_conformances_closed_by_fkey FOREIGN KEY (closed_by) REFERENCES users(id);

ALTER TABLE ONLY non_conformances
    ADD CONSTRAINT non_conformances_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY non_conformances
    ADD CONSTRAINT non_conformances_verification_requested_by_fkey FOREIGN KEY (verification_requested_by) REFERENCES users(id);

ALTER TABLE capa_actions ENABLE ROW LEVEL SECURITY;

ALTER TABLE capa_status_history ENABLE ROW LEVEL SECURITY;

ALTER TABLE capa_verifications ENABLE ROW LEVEL SECURITY;

ALTER TABLE corrective_actions ENABLE ROW LEVEL SECURITY;

ALTER TABLE defect_categories ENABLE ROW LEVEL SECURITY;

ALTER TABLE ncr_status_history ENABLE ROW LEVEL SECURITY;

ALTER TABLE non_conformances ENABLE ROW LEVEL SECURITY;

CREATE POLICY overdue_scan ON corrective_actions FOR SELECT TO "${flyway:user}" USING (true);

CREATE POLICY tenant_isolation ON capa_actions USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON capa_status_history USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON capa_verifications USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON corrective_actions USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON defect_categories USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON ncr_status_history USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON non_conformances USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

REVOKE ALL ON FUNCTION tenants_with_overdue_capas() FROM PUBLIC;
GRANT ALL ON FUNCTION tenants_with_overdue_capas() TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE capa_actions TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE capa_status_history TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE capa_verifications TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE corrective_actions TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE defect_categories TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE ncr_status_history TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE non_conformances TO ${approle};
