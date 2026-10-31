-- Instruments, calibration records, quality audits and findings.
-- ${approle} is the unprivileged role the application connects as.

SET LOCAL check_function_bodies = off;

CREATE FUNCTION protect_closed_audit_record() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM audits a
               WHERE a.tenant_id = OLD.tenant_id AND a.id = OLD.audit_id AND a.status = 'CLOSED') THEN
        RAISE EXCEPTION 'audit % is closed', OLD.audit_id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$;

-- A day wider than the reminder window, because tenants' days start at different moments.
CREATE FUNCTION tenants_with_instruments_due() RETURNS SETOF uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
    SELECT DISTINCT tenant_id FROM instruments WHERE status = 'ACTIVE' AND next_due_on < now()::date + 16;
$$;

CREATE TABLE audit_checklist_items (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    checklist_id uuid NOT NULL,
    "position" integer NOT NULL,
    question text NOT NULL,
    clause_reference text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL
);

ALTER TABLE ONLY audit_checklist_items FORCE ROW LEVEL SECURITY;

CREATE TABLE audit_checklists (
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

ALTER TABLE ONLY audit_checklists FORCE ROW LEVEL SECURITY;

CREATE TABLE audit_findings (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    audit_id uuid NOT NULL,
    audit_item_id uuid,
    grade text NOT NULL,
    statement text NOT NULL,
    evidence text NOT NULL,
    clause_reference text,
    ncr_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_audit_findings_grade CHECK ((grade = ANY (ARRAY['MAJOR_NONCONFORMITY'::text, 'MINOR_NONCONFORMITY'::text, 'OBSERVATION'::text, 'OPPORTUNITY'::text])))
);

ALTER TABLE ONLY audit_findings FORCE ROW LEVEL SECURITY;

-- The questions are copied here, so that changing a checklist cannot rewrite an audit already planned or finished.
CREATE TABLE audit_items (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    audit_id uuid NOT NULL,
    "position" integer NOT NULL,
    question text NOT NULL,
    clause_reference text,
    result text,
    note text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_audit_items_result CHECK ((result = ANY (ARRAY['CONFORMING'::text, 'NONCONFORMING'::text, 'NOT_APPLICABLE'::text])))
);

ALTER TABLE ONLY audit_items FORCE ROW LEVEL SECURITY;

CREATE TABLE audits (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    audit_number text NOT NULL,
    audit_type text NOT NULL,
    title text NOT NULL,
    scope text NOT NULL,
    facility_id uuid NOT NULL,
    supplier_id uuid,
    product_id uuid,
    checklist_id uuid NOT NULL,
    planned_on date NOT NULL,
    lead_auditor_id uuid NOT NULL,
    status text DEFAULT 'PLANNED'::text NOT NULL,
    summary text,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    closure_requested_by uuid,
    closure_requested_at timestamp with time zone,
    closure_note text,
    closed_by uuid,
    closed_at timestamp with time zone,
    cancel_reason text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_audits_closed CHECK (((status = 'CLOSED'::text) = (closed_at IS NOT NULL))),
    CONSTRAINT ck_audits_product CHECK (((audit_type <> 'PRODUCT'::text) OR (product_id IS NOT NULL))),
    CONSTRAINT ck_audits_status CHECK ((status = ANY (ARRAY['PLANNED'::text, 'IN_PROGRESS'::text, 'COMPLETED'::text, 'PENDING_CLOSURE'::text, 'CLOSED'::text, 'CANCELLED'::text]))),
    CONSTRAINT ck_audits_supplier CHECK (((audit_type <> 'SUPPLIER'::text) OR (supplier_id IS NOT NULL))),
    CONSTRAINT ck_audits_two_people CHECK (((closed_by IS NULL) OR (closed_by <> closure_requested_by))),
    CONSTRAINT ck_audits_type CHECK ((audit_type = ANY (ARRAY['INTERNAL'::text, 'SUPPLIER'::text, 'PROCESS'::text, 'PRODUCT'::text])))
);

ALTER TABLE ONLY audits FORCE ROW LEVEL SECURITY;

-- History, so a wrong record is corrected by adding another.
CREATE TABLE calibration_records (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    instrument_id uuid NOT NULL,
    calibrated_on date NOT NULL,
    result text NOT NULL,
    performed_by text NOT NULL,
    certificate_number text,
    notes text,
    next_due_on date NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_calibration_records_result CHECK ((result = ANY (ARRAY['PASS'::text, 'ADJUSTED'::text, 'FAIL'::text])))
);

ALTER TABLE ONLY calibration_records FORCE ROW LEVEL SECURITY;

-- Which instruments took an inspection's measurements, so that a failed calibration can name what it puts in doubt.
CREATE TABLE instrument_usages (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    instrument_id uuid NOT NULL,
    inspection_id uuid NOT NULL,
    inspection_number text NOT NULL,
    used_on date NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL
);

ALTER TABLE ONLY instrument_usages FORCE ROW LEVEL SECURITY;

CREATE TABLE instruments (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    instrument_code text NOT NULL,
    name text NOT NULL,
    category text NOT NULL,
    manufacturer text,
    model text,
    serial_number text,
    facility_id uuid NOT NULL,
    location text,
    custodian_id uuid,
    frequency_days integer NOT NULL,
    last_calibrated_on date,
    next_due_on date NOT NULL,
    status text DEFAULT 'ACTIVE'::text NOT NULL,
    status_reason text,
    due_reminded_at timestamp with time zone,
    overdue_notified_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_instruments_frequency CHECK ((frequency_days > 0)),
    CONSTRAINT ck_instruments_status CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'OUT_OF_SERVICE'::text, 'RETIRED'::text])))
);

ALTER TABLE ONLY instruments FORCE ROW LEVEL SECURITY;

ALTER TABLE ONLY audit_checklist_items
    ADD CONSTRAINT audit_checklist_items_pkey PRIMARY KEY (id);

ALTER TABLE ONLY audit_checklists
    ADD CONSTRAINT audit_checklists_pkey PRIMARY KEY (id);

ALTER TABLE ONLY audit_findings
    ADD CONSTRAINT audit_findings_pkey PRIMARY KEY (id);

ALTER TABLE ONLY audit_items
    ADD CONSTRAINT audit_items_pkey PRIMARY KEY (id);

ALTER TABLE ONLY audits
    ADD CONSTRAINT audits_pkey PRIMARY KEY (id);

ALTER TABLE ONLY calibration_records
    ADD CONSTRAINT calibration_records_pkey PRIMARY KEY (id);

ALTER TABLE ONLY instrument_usages
    ADD CONSTRAINT instrument_usages_pkey PRIMARY KEY (id);

ALTER TABLE ONLY instruments
    ADD CONSTRAINT instruments_pkey PRIMARY KEY (id);

ALTER TABLE ONLY audit_checklists
    ADD CONSTRAINT uq_audit_checklists_code UNIQUE (tenant_id, code);

ALTER TABLE ONLY audit_checklists
    ADD CONSTRAINT uq_audit_checklists_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY audit_findings
    ADD CONSTRAINT uq_audit_findings_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY audit_items
    ADD CONSTRAINT uq_audit_items_position UNIQUE (tenant_id, audit_id, "position");

ALTER TABLE ONLY audit_items
    ADD CONSTRAINT uq_audit_items_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY audits
    ADD CONSTRAINT uq_audits_number UNIQUE (tenant_id, audit_number);

ALTER TABLE ONLY audits
    ADD CONSTRAINT uq_audits_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY calibration_records
    ADD CONSTRAINT uq_calibration_records_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY instrument_usages
    ADD CONSTRAINT uq_instrument_usages UNIQUE (tenant_id, instrument_id, inspection_id);

ALTER TABLE ONLY instruments
    ADD CONSTRAINT uq_instruments_code UNIQUE (tenant_id, instrument_code);

ALTER TABLE ONLY instruments
    ADD CONSTRAINT uq_instruments_tenant_id UNIQUE (tenant_id, id);

CREATE INDEX ix_audit_checklist_items_checklist ON audit_checklist_items USING btree (tenant_id, checklist_id);

CREATE INDEX ix_audit_findings_audit ON audit_findings USING btree (tenant_id, audit_id);

CREATE INDEX ix_audits_planned ON audits USING btree (tenant_id, planned_on);

CREATE INDEX ix_calibration_records_instrument ON calibration_records USING btree (tenant_id, instrument_id, calibrated_on);

CREATE INDEX ix_instrument_usages_inspection ON instrument_usages USING btree (tenant_id, inspection_id);

CREATE INDEX ix_instruments_due ON instruments USING btree (tenant_id, next_due_on) WHERE (status = 'ACTIVE'::text);

CREATE INDEX ix_instruments_facility ON instruments USING btree (tenant_id, facility_id);

CREATE TRIGGER trg_audit_findings_closed BEFORE DELETE OR UPDATE ON audit_findings FOR EACH ROW EXECUTE FUNCTION protect_closed_audit_record();

CREATE TRIGGER trg_audit_items_closed BEFORE DELETE OR UPDATE ON audit_items FOR EACH ROW EXECUTE FUNCTION protect_closed_audit_record();

CREATE TRIGGER trg_calibration_records_history BEFORE DELETE OR UPDATE ON calibration_records FOR EACH ROW EXECUTE FUNCTION reject_change();

CREATE TRIGGER trg_instrument_usages_history BEFORE DELETE OR UPDATE ON instrument_usages FOR EACH ROW EXECUTE FUNCTION reject_change();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON audit_checklist_items FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON audit_checklists FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON audit_findings FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON audit_items FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON audits FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON calibration_records FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON instrument_usages FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON instruments FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE ONLY audit_checklist_items
    ADD CONSTRAINT audit_checklist_items_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY audit_checklists
    ADD CONSTRAINT audit_checklists_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY audit_findings
    ADD CONSTRAINT audit_findings_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY audit_items
    ADD CONSTRAINT audit_items_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY audits
    ADD CONSTRAINT audits_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY calibration_records
    ADD CONSTRAINT calibration_records_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY audit_checklist_items
    ADD CONSTRAINT fk_audit_checklist_items_checklist FOREIGN KEY (tenant_id, checklist_id) REFERENCES audit_checklists(tenant_id, id);

ALTER TABLE ONLY audit_findings
    ADD CONSTRAINT fk_audit_findings_audit FOREIGN KEY (tenant_id, audit_id) REFERENCES audits(tenant_id, id);

ALTER TABLE ONLY audit_findings
    ADD CONSTRAINT fk_audit_findings_item FOREIGN KEY (tenant_id, audit_item_id) REFERENCES audit_items(tenant_id, id);

ALTER TABLE ONLY audit_findings
    ADD CONSTRAINT fk_audit_findings_ncr FOREIGN KEY (tenant_id, ncr_id) REFERENCES non_conformances(tenant_id, id);

ALTER TABLE ONLY audit_items
    ADD CONSTRAINT fk_audit_items_audit FOREIGN KEY (tenant_id, audit_id) REFERENCES audits(tenant_id, id);

ALTER TABLE ONLY audits
    ADD CONSTRAINT fk_audits_checklist FOREIGN KEY (tenant_id, checklist_id) REFERENCES audit_checklists(tenant_id, id);

ALTER TABLE ONLY audits
    ADD CONSTRAINT fk_audits_facility FOREIGN KEY (tenant_id, facility_id) REFERENCES facilities(tenant_id, id);

ALTER TABLE ONLY audits
    ADD CONSTRAINT fk_audits_product FOREIGN KEY (tenant_id, product_id) REFERENCES products(tenant_id, id);

ALTER TABLE ONLY audits
    ADD CONSTRAINT fk_audits_supplier FOREIGN KEY (tenant_id, supplier_id) REFERENCES suppliers(tenant_id, id);

ALTER TABLE ONLY calibration_records
    ADD CONSTRAINT fk_calibration_records_instrument FOREIGN KEY (tenant_id, instrument_id) REFERENCES instruments(tenant_id, id);

ALTER TABLE ONLY instrument_usages
    ADD CONSTRAINT fk_instrument_usages_inspection FOREIGN KEY (tenant_id, inspection_id) REFERENCES inspections(tenant_id, id);

ALTER TABLE ONLY instrument_usages
    ADD CONSTRAINT fk_instrument_usages_instrument FOREIGN KEY (tenant_id, instrument_id) REFERENCES instruments(tenant_id, id);

ALTER TABLE ONLY instruments
    ADD CONSTRAINT fk_instruments_facility FOREIGN KEY (tenant_id, facility_id) REFERENCES facilities(tenant_id, id);

ALTER TABLE ONLY non_conformances
    ADD CONSTRAINT fk_ncr_audit FOREIGN KEY (tenant_id, audit_id) REFERENCES audits(tenant_id, id);

ALTER TABLE ONLY non_conformances
    ADD CONSTRAINT fk_ncr_audit_finding FOREIGN KEY (tenant_id, audit_finding_id) REFERENCES audit_findings(tenant_id, id);

ALTER TABLE ONLY instrument_usages
    ADD CONSTRAINT instrument_usages_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY instruments
    ADD CONSTRAINT instruments_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE audit_checklist_items ENABLE ROW LEVEL SECURITY;

ALTER TABLE audit_checklists ENABLE ROW LEVEL SECURITY;

ALTER TABLE audit_findings ENABLE ROW LEVEL SECURITY;

ALTER TABLE audit_items ENABLE ROW LEVEL SECURITY;

ALTER TABLE audits ENABLE ROW LEVEL SECURITY;

ALTER TABLE calibration_records ENABLE ROW LEVEL SECURITY;

CREATE POLICY due_scan ON instruments FOR SELECT TO "${flyway:user}" USING (true);

ALTER TABLE instrument_usages ENABLE ROW LEVEL SECURITY;

ALTER TABLE instruments ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON audit_checklist_items USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON audit_checklists USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON audit_findings USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON audit_items USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON audits USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON calibration_records USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON instrument_usages USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON instruments USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

REVOKE ALL ON FUNCTION tenants_with_instruments_due() FROM PUBLIC;
GRANT ALL ON FUNCTION tenants_with_instruments_due() TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE audit_checklist_items TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE audit_checklists TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE audit_findings TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE audit_items TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE audits TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE calibration_records TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE instrument_usages TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE instruments TO ${approle};
