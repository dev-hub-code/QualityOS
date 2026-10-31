INSERT INTO permissions (code, module, description) VALUES
    ('AUDIT_CLOSE', 'Audit', 'Approve the closure of quality audits');
ALTER TABLE role_permissions NO FORCE ROW LEVEL SECURITY;
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'AUDIT_CLOSE'
WHERE r.code IN ('ORGANISATION_OWNER', 'ORGANISATION_ADMIN', 'QUALITY_MANAGER', 'PLANT_MANAGER');
ALTER TABLE role_permissions FORCE ROW LEVEL SECURITY;

CREATE TABLE instruments (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    instrument_code TEXT NOT NULL,
    name TEXT NOT NULL,
    category TEXT NOT NULL,
    manufacturer TEXT,
    model TEXT,
    serial_number TEXT,
    facility_id UUID NOT NULL,
    location TEXT,
    custodian_id UUID,
    frequency_days INTEGER NOT NULL,
    last_calibrated_on DATE,
    next_due_on DATE NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    status_reason TEXT,
    due_reminded_at TIMESTAMPTZ,
    overdue_notified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_instruments_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_instruments_code UNIQUE (tenant_id, instrument_code),
    CONSTRAINT fk_instruments_facility FOREIGN KEY (tenant_id, facility_id) REFERENCES facilities (tenant_id, id),
    CONSTRAINT ck_instruments_status CHECK (status IN ('ACTIVE', 'OUT_OF_SERVICE', 'RETIRED')),
    CONSTRAINT ck_instruments_frequency CHECK (frequency_days > 0)
);
SELECT apply_tenant_standards('instruments');
CREATE INDEX ix_instruments_due ON instruments (tenant_id, next_due_on) WHERE status = 'ACTIVE';
CREATE INDEX ix_instruments_facility ON instruments (tenant_id, facility_id);

-- History, so a wrong record is corrected by adding another.
CREATE TABLE calibration_records (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    instrument_id UUID NOT NULL,
    calibrated_on DATE NOT NULL,
    result TEXT NOT NULL,
    performed_by TEXT NOT NULL,
    certificate_number TEXT,
    notes TEXT,
    next_due_on DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_calibration_records_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_calibration_records_instrument FOREIGN KEY (tenant_id, instrument_id)
        REFERENCES instruments (tenant_id, id),
    CONSTRAINT ck_calibration_records_result CHECK (result IN ('PASS', 'ADJUSTED', 'FAIL'))
);
SELECT apply_tenant_standards('calibration_records');
CREATE INDEX ix_calibration_records_instrument ON calibration_records (tenant_id, instrument_id, calibrated_on);
CREATE TRIGGER trg_calibration_records_history BEFORE UPDATE OR DELETE ON calibration_records
    FOR EACH ROW EXECUTE FUNCTION reject_change();

-- Which instruments took an inspection's measurements, so that a failed calibration can name what it puts in doubt.
CREATE TABLE instrument_usages (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    instrument_id UUID NOT NULL,
    inspection_id UUID NOT NULL,
    inspection_number TEXT NOT NULL,
    used_on DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_instrument_usages UNIQUE (tenant_id, instrument_id, inspection_id),
    CONSTRAINT fk_instrument_usages_instrument FOREIGN KEY (tenant_id, instrument_id)
        REFERENCES instruments (tenant_id, id),
    CONSTRAINT fk_instrument_usages_inspection FOREIGN KEY (tenant_id, inspection_id)
        REFERENCES inspections (tenant_id, id)
);
SELECT apply_tenant_standards('instrument_usages');
CREATE INDEX ix_instrument_usages_inspection ON instrument_usages (tenant_id, inspection_id);
CREATE TRIGGER trg_instrument_usages_history BEFORE UPDATE OR DELETE ON instrument_usages
    FOR EACH ROW EXECUTE FUNCTION reject_change();

DO $$
BEGIN
    EXECUTE format('CREATE POLICY due_scan ON instruments FOR SELECT TO %I USING (true)', current_user);
END;
$$;

-- A day wider than the reminder window, because tenants' days start at different moments.
CREATE FUNCTION tenants_with_instruments_due() RETURNS SETOF UUID
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS
$$
    SELECT DISTINCT tenant_id FROM instruments WHERE status = 'ACTIVE' AND next_due_on < now()::date + 16;
$$;
REVOKE ALL ON FUNCTION tenants_with_instruments_due() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenants_with_instruments_due() TO ${approle};

CREATE TABLE audit_checklists (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    code TEXT NOT NULL,
    name TEXT NOT NULL,
    archived_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_audit_checklists_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_audit_checklists_code UNIQUE (tenant_id, code)
);
SELECT apply_tenant_standards('audit_checklists');

CREATE TABLE audit_checklist_items (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    checklist_id UUID NOT NULL,
    position INTEGER NOT NULL,
    question TEXT NOT NULL,
    clause_reference TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_audit_checklist_items_checklist FOREIGN KEY (tenant_id, checklist_id)
        REFERENCES audit_checklists (tenant_id, id)
);
SELECT apply_tenant_standards('audit_checklist_items');
CREATE INDEX ix_audit_checklist_items_checklist ON audit_checklist_items (tenant_id, checklist_id);

CREATE TABLE audits (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    audit_number TEXT NOT NULL,
    audit_type TEXT NOT NULL,
    title TEXT NOT NULL,
    scope TEXT NOT NULL,
    facility_id UUID NOT NULL,
    supplier_id UUID,
    product_id UUID,
    checklist_id UUID NOT NULL,
    planned_on DATE NOT NULL,
    lead_auditor_id UUID NOT NULL,
    status TEXT NOT NULL DEFAULT 'PLANNED',
    summary TEXT,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    closure_requested_by UUID,
    closure_requested_at TIMESTAMPTZ,
    closure_note TEXT,
    closed_by UUID,
    closed_at TIMESTAMPTZ,
    cancel_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_audits_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_audits_number UNIQUE (tenant_id, audit_number),
    CONSTRAINT fk_audits_facility FOREIGN KEY (tenant_id, facility_id) REFERENCES facilities (tenant_id, id),
    CONSTRAINT fk_audits_supplier FOREIGN KEY (tenant_id, supplier_id) REFERENCES suppliers (tenant_id, id),
    CONSTRAINT fk_audits_product FOREIGN KEY (tenant_id, product_id) REFERENCES products (tenant_id, id),
    CONSTRAINT fk_audits_checklist FOREIGN KEY (tenant_id, checklist_id) REFERENCES audit_checklists (tenant_id, id),
    CONSTRAINT ck_audits_type CHECK (audit_type IN ('INTERNAL', 'SUPPLIER', 'PROCESS', 'PRODUCT')),
    CONSTRAINT ck_audits_status CHECK (status IN ('PLANNED', 'IN_PROGRESS', 'COMPLETED', 'PENDING_CLOSURE', 'CLOSED',
        'CANCELLED')),
    CONSTRAINT ck_audits_supplier CHECK (audit_type <> 'SUPPLIER' OR supplier_id IS NOT NULL),
    CONSTRAINT ck_audits_product CHECK (audit_type <> 'PRODUCT' OR product_id IS NOT NULL),
    CONSTRAINT ck_audits_closed CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL)),
    CONSTRAINT ck_audits_two_people CHECK (closed_by IS NULL OR closed_by <> closure_requested_by)
);
SELECT apply_tenant_standards('audits');
CREATE INDEX ix_audits_planned ON audits (tenant_id, planned_on);

-- The questions are copied here, so that changing a checklist cannot rewrite an audit already planned or finished.
CREATE TABLE audit_items (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    audit_id UUID NOT NULL,
    position INTEGER NOT NULL,
    question TEXT NOT NULL,
    clause_reference TEXT,
    result TEXT,
    note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_audit_items_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_audit_items_position UNIQUE (tenant_id, audit_id, position),
    CONSTRAINT fk_audit_items_audit FOREIGN KEY (tenant_id, audit_id) REFERENCES audits (tenant_id, id),
    CONSTRAINT ck_audit_items_result CHECK (result IN ('CONFORMING', 'NONCONFORMING', 'NOT_APPLICABLE'))
);
SELECT apply_tenant_standards('audit_items');

CREATE TABLE audit_findings (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    audit_id UUID NOT NULL,
    audit_item_id UUID,
    grade TEXT NOT NULL,
    statement TEXT NOT NULL,
    evidence TEXT NOT NULL,
    clause_reference TEXT,
    ncr_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_audit_findings_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_audit_findings_audit FOREIGN KEY (tenant_id, audit_id) REFERENCES audits (tenant_id, id),
    CONSTRAINT fk_audit_findings_item FOREIGN KEY (tenant_id, audit_item_id) REFERENCES audit_items (tenant_id, id),
    CONSTRAINT fk_audit_findings_ncr FOREIGN KEY (tenant_id, ncr_id) REFERENCES non_conformances (tenant_id, id),
    CONSTRAINT ck_audit_findings_grade CHECK (grade IN ('MAJOR_NONCONFORMITY', 'MINOR_NONCONFORMITY', 'OBSERVATION',
        'OPPORTUNITY'))
);
SELECT apply_tenant_standards('audit_findings');
CREATE INDEX ix_audit_findings_audit ON audit_findings (tenant_id, audit_id);

CREATE FUNCTION protect_closed_audit_record() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF EXISTS (SELECT 1 FROM audits a
               WHERE a.tenant_id = OLD.tenant_id AND a.id = OLD.audit_id AND a.status = 'CLOSED') THEN
        RAISE EXCEPTION 'audit % is closed', OLD.audit_id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$;
CREATE TRIGGER trg_audit_items_closed BEFORE UPDATE OR DELETE ON audit_items
    FOR EACH ROW EXECUTE FUNCTION protect_closed_audit_record();
CREATE TRIGGER trg_audit_findings_closed BEFORE UPDATE OR DELETE ON audit_findings
    FOR EACH ROW EXECUTE FUNCTION protect_closed_audit_record();

ALTER TABLE non_conformances
    ADD COLUMN audit_id UUID,
    ADD COLUMN audit_finding_id UUID,
    DROP CONSTRAINT ck_ncr_source,
    ADD CONSTRAINT ck_ncr_source CHECK (source IN ('INSPECTION', 'MANUAL', 'AUDIT')),
    ADD CONSTRAINT ck_ncr_audit_source CHECK ((source = 'AUDIT') = (audit_finding_id IS NOT NULL)),
    ADD CONSTRAINT fk_ncr_audit FOREIGN KEY (tenant_id, audit_id) REFERENCES audits (tenant_id, id),
    ADD CONSTRAINT fk_ncr_audit_finding FOREIGN KEY (tenant_id, audit_finding_id)
        REFERENCES audit_findings (tenant_id, id);
CREATE UNIQUE INDEX uq_ncr_audit_finding ON non_conformances (tenant_id, audit_finding_id)
    WHERE audit_finding_id IS NOT NULL;
