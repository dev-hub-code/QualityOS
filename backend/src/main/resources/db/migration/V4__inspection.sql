-- Templates, sampling, inspections, approval and disposition.
-- ${approle} is the unprivileged role the application connects as.

SET LOCAL check_function_bodies = off;

CREATE FUNCTION protect_inspection_specification() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.spec_snapshot IS DISTINCT FROM OLD.spec_snapshot
        OR NEW.template_version_id IS DISTINCT FROM OLD.template_version_id
        OR NEW.sample_size IS DISTINCT FROM OLD.sample_size THEN
        RAISE EXCEPTION 'The specification of an inspection cannot be changed';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION protect_published_parameters() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    version_id UUID := CASE WHEN TG_OP = 'INSERT' THEN NEW.template_version_id ELSE OLD.template_version_id END;
BEGIN
    IF EXISTS (SELECT 1 FROM inspection_template_versions WHERE id = version_id AND status <> 'DRAFT') THEN
        RAISE EXCEPTION 'Parameters of a published template version cannot be changed';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION protect_published_version() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF OLD.status = 'DRAFT' THEN
        IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'A published template version cannot be deleted';
    END IF;
    -- Being superseded is the only change a published version may undergo.
    IF (to_jsonb(NEW) - ARRAY['status', 'effective_to', 'updated_at', 'updated_by', 'version'])
        IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['status', 'effective_to', 'updated_at', 'updated_by', 'version'])
        OR NEW.status = 'DRAFT' THEN
        RAISE EXCEPTION 'A published template version cannot be changed';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION protect_submitted_results() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    owner_id UUID := CASE WHEN TG_OP = 'INSERT' THEN NEW.inspection_id ELSE OLD.inspection_id END;
BEGIN
    IF EXISTS (SELECT 1 FROM inspections WHERE id = owner_id AND status NOT IN ('PENDING', 'IN_PROGRESS')) THEN
        RAISE EXCEPTION 'Results of a submitted inspection cannot be changed';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION reject_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'This record cannot be changed';
END;
$$;

-- Every decision on an inspection, in order. Rows are only ever added.
CREATE TABLE inspection_approvals (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    inspection_id uuid NOT NULL,
    decision text NOT NULL,
    note text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL
);

ALTER TABLE ONLY inspection_approvals FORCE ROW LEVEL SECURITY;

CREATE TABLE inspection_parameters (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    template_version_id uuid NOT NULL,
    code text NOT NULL,
    name text NOT NULL,
    description text,
    data_type text NOT NULL,
    uom_id uuid,
    nominal_value numeric(18,6),
    min_value numeric(18,6),
    max_value numeric(18,6),
    tolerance numeric(18,6),
    expected_text text,
    criticality text DEFAULT 'MINOR'::text NOT NULL,
    mandatory boolean DEFAULT true NOT NULL,
    per_sample boolean DEFAULT true NOT NULL,
    evidence_required boolean DEFAULT false NOT NULL,
    inspection_method text,
    remarks text,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_inspection_parameters_criticality CHECK ((criticality = ANY (ARRAY['CRITICAL'::text, 'MAJOR'::text, 'MINOR'::text]))),
    CONSTRAINT ck_inspection_parameters_range CHECK (((min_value IS NULL) OR (max_value IS NULL) OR (min_value <= max_value))),
    CONSTRAINT ck_inspection_parameters_tolerance CHECK (((tolerance IS NULL) OR (tolerance >= (0)::numeric))),
    CONSTRAINT ck_inspection_parameters_type CHECK ((data_type = ANY (ARRAY['NUMERIC'::text, 'TEXT'::text, 'BOOLEAN'::text, 'DATE'::text])))
);

ALTER TABLE ONLY inspection_parameters FORCE ROW LEVEL SECURITY;

CREATE TABLE inspection_results (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    inspection_id uuid NOT NULL,
    parameter_code text NOT NULL,
    sample_number integer NOT NULL,
    value_numeric numeric(18,6),
    value_text text,
    value_boolean boolean,
    value_date date,
    evaluation text NOT NULL,
    remarks text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_inspection_results_evaluation CHECK ((evaluation = ANY (ARRAY['PASS'::text, 'FAIL'::text, 'PENDING_REVIEW'::text]))),
    CONSTRAINT ck_inspection_results_sample CHECK ((sample_number > 0))
);

ALTER TABLE ONLY inspection_results FORCE ROW LEVEL SECURITY;

CREATE TABLE inspection_template_versions (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    template_id uuid NOT NULL,
    version_number integer NOT NULL,
    status text DEFAULT 'DRAFT'::text NOT NULL,
    change_note text,
    sampling_type text DEFAULT 'FIXED'::text NOT NULL,
    sample_size integer,
    sample_percent numeric(5,2),
    acceptance_number integer DEFAULT 0 NOT NULL,
    effective_from date,
    effective_to date,
    published_by uuid,
    published_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_template_versions_acceptance CHECK ((acceptance_number >= 0)),
    CONSTRAINT ck_template_versions_sampling CHECK ((((sampling_type = 'FIXED'::text) AND (sample_size > 0)) OR ((sampling_type = 'PERCENTAGE'::text) AND (sample_percent > (0)::numeric) AND (sample_percent <= (100)::numeric)) OR (sampling_type = 'FULL'::text))),
    CONSTRAINT ck_template_versions_status CHECK ((status = ANY (ARRAY['DRAFT'::text, 'PUBLISHED'::text, 'SUPERSEDED'::text])))
);

ALTER TABLE ONLY inspection_template_versions FORCE ROW LEVEL SECURITY;

CREATE TABLE inspection_templates (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    code text NOT NULL,
    name text NOT NULL,
    inspection_type text NOT NULL,
    material_id uuid,
    product_id uuid,
    archived_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_inspection_templates_target CHECK (((material_id IS NULL) OR (product_id IS NULL))),
    CONSTRAINT ck_inspection_templates_type CHECK ((inspection_type = ANY (ARRAY['IQC'::text, 'IPQC'::text, 'FQC'::text, 'OQC'::text, 'SUPPLIER'::text, 'PACKAGING'::text, 'VISUAL'::text, 'DIMENSIONAL'::text, 'FUNCTIONAL'::text])))
);

ALTER TABLE ONLY inspection_templates FORCE ROW LEVEL SECURITY;

CREATE TABLE inspections (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    inspection_number text NOT NULL,
    inspection_type text NOT NULL,
    template_version_id uuid NOT NULL,
    spec_snapshot jsonb NOT NULL,
    receipt_item_id uuid,
    supplier_id uuid,
    facility_id uuid NOT NULL,
    material_id uuid,
    batch_number text NOT NULL,
    quantity numeric(18,6) NOT NULL,
    sample_size integer NOT NULL,
    status text DEFAULT 'PENDING'::text NOT NULL,
    evaluated_result text,
    final_result text,
    disposition text,
    submitted_by uuid,
    submitted_at timestamp with time zone,
    approved_by uuid,
    approved_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    production_batch_id uuid,
    return_item_id uuid,
    CONSTRAINT ck_inspections_disposition CHECK ((disposition = ANY (ARRAY['ACCEPT'::text, 'REJECT'::text, 'HOLD'::text, 'REWORK'::text, 'CONDITIONAL_ACCEPTANCE'::text]))),
    CONSTRAINT ck_inspections_evaluated CHECK ((evaluated_result = ANY (ARRAY['PASS'::text, 'FAIL'::text, 'PENDING_REVIEW'::text]))),
    CONSTRAINT ck_inspections_final CHECK ((final_result = ANY (ARRAY['PASS'::text, 'FAIL'::text]))),
    CONSTRAINT ck_inspections_no_silent_release CHECK (((disposition <> 'ACCEPT'::text) OR (final_result = 'PASS'::text))),
    CONSTRAINT ck_inspections_return_of_batch CHECK (((return_item_id IS NULL) OR (production_batch_id IS NOT NULL))),
    CONSTRAINT ck_inspections_status CHECK ((status = ANY (ARRAY['PENDING'::text, 'IN_PROGRESS'::text, 'SUBMITTED'::text, 'APPROVED'::text, 'COMPLETED'::text, 'CANCELLED'::text]))),
    CONSTRAINT ck_inspections_subject CHECK ((num_nonnulls(receipt_item_id, production_batch_id) = 1))
);

ALTER TABLE ONLY inspections FORCE ROW LEVEL SECURITY;

ALTER TABLE ONLY inspection_approvals
    ADD CONSTRAINT inspection_approvals_pkey PRIMARY KEY (id);

ALTER TABLE ONLY inspection_parameters
    ADD CONSTRAINT inspection_parameters_pkey PRIMARY KEY (id);

ALTER TABLE ONLY inspection_results
    ADD CONSTRAINT inspection_results_pkey PRIMARY KEY (id);

ALTER TABLE ONLY inspection_template_versions
    ADD CONSTRAINT inspection_template_versions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY inspection_templates
    ADD CONSTRAINT inspection_templates_pkey PRIMARY KEY (id);

ALTER TABLE ONLY inspections
    ADD CONSTRAINT inspections_pkey PRIMARY KEY (id);

ALTER TABLE ONLY inspection_approvals
    ADD CONSTRAINT uq_inspection_approvals_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY inspection_parameters
    ADD CONSTRAINT uq_inspection_parameters_code UNIQUE (tenant_id, template_version_id, code);

ALTER TABLE ONLY inspection_parameters
    ADD CONSTRAINT uq_inspection_parameters_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY inspection_results
    ADD CONSTRAINT uq_inspection_results_sample UNIQUE (tenant_id, inspection_id, parameter_code, sample_number);

ALTER TABLE ONLY inspection_results
    ADD CONSTRAINT uq_inspection_results_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY inspection_templates
    ADD CONSTRAINT uq_inspection_templates_code UNIQUE (tenant_id, code);

ALTER TABLE ONLY inspection_templates
    ADD CONSTRAINT uq_inspection_templates_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY inspections
    ADD CONSTRAINT uq_inspections_number UNIQUE (tenant_id, inspection_number);

ALTER TABLE ONLY inspections
    ADD CONSTRAINT uq_inspections_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY inspection_template_versions
    ADD CONSTRAINT uq_template_versions_number UNIQUE (tenant_id, template_id, version_number);

ALTER TABLE ONLY inspection_template_versions
    ADD CONSTRAINT uq_template_versions_tenant_id UNIQUE (tenant_id, id);

CREATE INDEX ix_inspection_approvals_inspection ON inspection_approvals USING btree (tenant_id, inspection_id);

CREATE INDEX ix_inspections_batch ON inspections USING btree (tenant_id, batch_number);

CREATE INDEX ix_inspections_decided ON inspections USING btree (tenant_id, approved_at) WHERE (final_result IS NOT NULL);

CREATE INDEX ix_inspections_status ON inspections USING btree (tenant_id, status);

CREATE INDEX ix_inspections_supplier ON inspections USING btree (tenant_id, supplier_id);

CREATE UNIQUE INDEX uq_inspections_active_batch ON inspections USING btree (tenant_id, production_batch_id) WHERE ((production_batch_id IS NOT NULL) AND (return_item_id IS NULL) AND (status <> ALL (ARRAY['CANCELLED'::text, 'COMPLETED'::text])));

CREATE UNIQUE INDEX uq_inspections_active_item ON inspections USING btree (tenant_id, receipt_item_id) WHERE (status <> 'CANCELLED'::text);

CREATE UNIQUE INDEX uq_inspections_active_return ON inspections USING btree (tenant_id, return_item_id) WHERE ((return_item_id IS NOT NULL) AND (status <> ALL (ARRAY['CANCELLED'::text, 'COMPLETED'::text])));

CREATE UNIQUE INDEX uq_template_versions_draft ON inspection_template_versions USING btree (tenant_id, template_id) WHERE (status = 'DRAFT'::text);

CREATE TRIGGER trg_append_only BEFORE DELETE OR UPDATE ON inspection_approvals FOR EACH ROW EXECUTE FUNCTION reject_change();

CREATE TRIGGER trg_protect_parameters BEFORE INSERT OR DELETE OR UPDATE ON inspection_parameters FOR EACH ROW EXECUTE FUNCTION protect_published_parameters();

CREATE TRIGGER trg_protect_results BEFORE INSERT OR DELETE OR UPDATE ON inspection_results FOR EACH ROW EXECUTE FUNCTION protect_submitted_results();

CREATE TRIGGER trg_protect_specification BEFORE UPDATE ON inspections FOR EACH ROW EXECUTE FUNCTION protect_inspection_specification();

CREATE TRIGGER trg_protect_version BEFORE DELETE OR UPDATE ON inspection_template_versions FOR EACH ROW EXECUTE FUNCTION protect_published_version();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON inspection_approvals FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON inspection_parameters FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON inspection_results FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON inspection_template_versions FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON inspection_templates FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON inspections FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE ONLY inspection_approvals
    ADD CONSTRAINT fk_inspection_approvals_inspection FOREIGN KEY (tenant_id, inspection_id) REFERENCES inspections(tenant_id, id);

ALTER TABLE ONLY inspection_parameters
    ADD CONSTRAINT fk_inspection_parameters_version FOREIGN KEY (tenant_id, template_version_id) REFERENCES inspection_template_versions(tenant_id, id);

ALTER TABLE ONLY inspection_results
    ADD CONSTRAINT fk_inspection_results_inspection FOREIGN KEY (tenant_id, inspection_id) REFERENCES inspections(tenant_id, id);

ALTER TABLE ONLY inspection_templates
    ADD CONSTRAINT fk_inspection_templates_material_id FOREIGN KEY (tenant_id, material_id) REFERENCES materials(tenant_id, id);

ALTER TABLE ONLY inspection_templates
    ADD CONSTRAINT fk_inspection_templates_product_id FOREIGN KEY (tenant_id, product_id) REFERENCES products(tenant_id, id);

ALTER TABLE ONLY inspections
    ADD CONSTRAINT fk_inspections_facility FOREIGN KEY (tenant_id, facility_id) REFERENCES facilities(tenant_id, id);

ALTER TABLE ONLY inspections
    ADD CONSTRAINT fk_inspections_material FOREIGN KEY (tenant_id, material_id) REFERENCES materials(tenant_id, id);

ALTER TABLE ONLY inspections
    ADD CONSTRAINT fk_inspections_receipt_item FOREIGN KEY (tenant_id, receipt_item_id) REFERENCES material_receipt_items(tenant_id, id);

ALTER TABLE ONLY inspections
    ADD CONSTRAINT fk_inspections_supplier FOREIGN KEY (tenant_id, supplier_id) REFERENCES suppliers(tenant_id, id);

ALTER TABLE ONLY inspections
    ADD CONSTRAINT fk_inspections_template_version FOREIGN KEY (tenant_id, template_version_id) REFERENCES inspection_template_versions(tenant_id, id);

ALTER TABLE ONLY inspection_template_versions
    ADD CONSTRAINT fk_template_versions_template_id FOREIGN KEY (tenant_id, template_id) REFERENCES inspection_templates(tenant_id, id);

ALTER TABLE ONLY inspection_approvals
    ADD CONSTRAINT inspection_approvals_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY inspection_parameters
    ADD CONSTRAINT inspection_parameters_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY inspection_parameters
    ADD CONSTRAINT inspection_parameters_uom_id_fkey FOREIGN KEY (uom_id) REFERENCES units_of_measure(id);

ALTER TABLE ONLY inspection_results
    ADD CONSTRAINT inspection_results_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY inspection_template_versions
    ADD CONSTRAINT inspection_template_versions_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY inspection_templates
    ADD CONSTRAINT inspection_templates_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY inspections
    ADD CONSTRAINT inspections_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE inspection_approvals ENABLE ROW LEVEL SECURITY;

ALTER TABLE inspection_parameters ENABLE ROW LEVEL SECURITY;

ALTER TABLE inspection_results ENABLE ROW LEVEL SECURITY;

ALTER TABLE inspection_template_versions ENABLE ROW LEVEL SECURITY;

ALTER TABLE inspection_templates ENABLE ROW LEVEL SECURITY;

ALTER TABLE inspections ENABLE ROW LEVEL SECURITY;

CREATE POLICY platform_scan ON inspections FOR SELECT TO "${flyway:user}" USING (true);

CREATE POLICY tenant_isolation ON inspection_approvals USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON inspection_parameters USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON inspection_results USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON inspection_template_versions USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON inspection_templates USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON inspections USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE inspection_approvals TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE inspection_parameters TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE inspection_results TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE inspection_template_versions TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE inspection_templates TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE inspections TO ${approle};
