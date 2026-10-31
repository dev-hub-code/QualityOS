-- Inspection templates, inspections and their results. History here is protected in the database as well as in
-- code: published template versions, specification snapshots, submitted results and decisions cannot be changed.

ALTER TABLE material_receipt_items DROP CONSTRAINT ck_receipt_items_inspection;
ALTER TABLE material_receipt_items ADD CONSTRAINT ck_receipt_items_inspection CHECK (inspection_status IN
    ('NOT_REQUIRED', 'PENDING', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'));
ALTER TABLE material_receipt_items ADD COLUMN disposition TEXT;
ALTER TABLE material_receipt_items ADD CONSTRAINT ck_receipt_items_disposition CHECK (disposition IN
    ('ACCEPT', 'REJECT', 'HOLD', 'REWORK', 'CONDITIONAL_ACCEPTANCE'));
CREATE INDEX ix_receipt_items_inspection ON material_receipt_items (tenant_id, inspection_status);

CREATE TABLE inspection_templates (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    code TEXT NOT NULL,
    name TEXT NOT NULL,
    inspection_type TEXT NOT NULL,
    material_id UUID,
    product_id UUID,
    archived_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_inspection_templates_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_inspection_templates_code UNIQUE (tenant_id, code),
    CONSTRAINT fk_inspection_templates_material_id FOREIGN KEY (tenant_id, material_id) REFERENCES materials (tenant_id, id),
    CONSTRAINT fk_inspection_templates_product_id FOREIGN KEY (tenant_id, product_id) REFERENCES products (tenant_id, id),
    CONSTRAINT ck_inspection_templates_type CHECK (inspection_type IN
        ('IQC', 'IPQC', 'FQC', 'OQC', 'SUPPLIER', 'PACKAGING', 'VISUAL', 'DIMENSIONAL', 'FUNCTIONAL')),
    CONSTRAINT ck_inspection_templates_target CHECK (material_id IS NULL OR product_id IS NULL)
);
SELECT apply_tenant_standards('inspection_templates');

CREATE TABLE inspection_template_versions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    template_id UUID NOT NULL,
    version_number INT NOT NULL,
    status TEXT NOT NULL DEFAULT 'DRAFT',
    change_note TEXT,
    sampling_type TEXT NOT NULL DEFAULT 'FIXED',
    sample_size INT,
    sample_percent NUMERIC(5, 2),
    acceptance_number INT NOT NULL DEFAULT 0,
    effective_from DATE,
    effective_to DATE,
    published_by UUID,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_template_versions_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_template_versions_number UNIQUE (tenant_id, template_id, version_number),
    CONSTRAINT fk_template_versions_template_id FOREIGN KEY (tenant_id, template_id) REFERENCES inspection_templates (tenant_id, id),
    CONSTRAINT ck_template_versions_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'SUPERSEDED')),
    CONSTRAINT ck_template_versions_sampling CHECK (
        (sampling_type = 'FIXED' AND sample_size > 0)
        OR (sampling_type = 'PERCENTAGE' AND sample_percent > 0 AND sample_percent <= 100)
        OR sampling_type = 'FULL'),
    CONSTRAINT ck_template_versions_acceptance CHECK (acceptance_number >= 0)
);
-- Only one draft at a time, so that two people cannot prepare competing next versions.
CREATE UNIQUE INDEX uq_template_versions_draft ON inspection_template_versions (tenant_id, template_id)
    WHERE status = 'DRAFT';
SELECT apply_tenant_standards('inspection_template_versions');

CREATE TABLE inspection_parameters (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    template_version_id UUID NOT NULL,
    code TEXT NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    data_type TEXT NOT NULL,
    uom_id UUID REFERENCES units_of_measure (id),
    nominal_value NUMERIC(18, 6),
    min_value NUMERIC(18, 6),
    max_value NUMERIC(18, 6),
    tolerance NUMERIC(18, 6),
    expected_text TEXT,
    criticality TEXT NOT NULL DEFAULT 'MINOR',
    mandatory BOOLEAN NOT NULL DEFAULT TRUE,
    per_sample BOOLEAN NOT NULL DEFAULT TRUE,
    evidence_required BOOLEAN NOT NULL DEFAULT FALSE,
    inspection_method TEXT,
    remarks TEXT,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_inspection_parameters_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_inspection_parameters_code UNIQUE (tenant_id, template_version_id, code),
    CONSTRAINT fk_inspection_parameters_version FOREIGN KEY (tenant_id, template_version_id) REFERENCES inspection_template_versions (tenant_id, id),
    CONSTRAINT ck_inspection_parameters_type CHECK (data_type IN ('NUMERIC', 'TEXT', 'BOOLEAN', 'DATE')),
    CONSTRAINT ck_inspection_parameters_criticality CHECK (criticality IN ('CRITICAL', 'MAJOR', 'MINOR')),
    CONSTRAINT ck_inspection_parameters_range CHECK (min_value IS NULL OR max_value IS NULL OR min_value <= max_value),
    CONSTRAINT ck_inspection_parameters_tolerance CHECK (tolerance IS NULL OR tolerance >= 0)
);
SELECT apply_tenant_standards('inspection_parameters');

CREATE TABLE inspections (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    inspection_number TEXT NOT NULL,
    inspection_type TEXT NOT NULL,
    template_version_id UUID NOT NULL,
    spec_snapshot JSONB NOT NULL,
    receipt_item_id UUID NOT NULL,
    supplier_id UUID NOT NULL,
    facility_id UUID NOT NULL,
    material_id UUID NOT NULL,
    batch_number TEXT NOT NULL,
    quantity NUMERIC(18, 6) NOT NULL,
    sample_size INT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    evaluated_result TEXT,
    final_result TEXT,
    disposition TEXT,
    submitted_by UUID,
    submitted_at TIMESTAMPTZ,
    approved_by UUID,
    approved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_inspections_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_inspections_number UNIQUE (tenant_id, inspection_number),
    CONSTRAINT fk_inspections_template_version FOREIGN KEY (tenant_id, template_version_id) REFERENCES inspection_template_versions (tenant_id, id),
    CONSTRAINT fk_inspections_receipt_item FOREIGN KEY (tenant_id, receipt_item_id) REFERENCES material_receipt_items (tenant_id, id),
    CONSTRAINT fk_inspections_supplier FOREIGN KEY (tenant_id, supplier_id) REFERENCES suppliers (tenant_id, id),
    CONSTRAINT fk_inspections_facility FOREIGN KEY (tenant_id, facility_id) REFERENCES facilities (tenant_id, id),
    CONSTRAINT fk_inspections_material FOREIGN KEY (tenant_id, material_id) REFERENCES materials (tenant_id, id),
    CONSTRAINT ck_inspections_status CHECK (status IN
        ('PENDING', 'IN_PROGRESS', 'SUBMITTED', 'APPROVED', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_inspections_evaluated CHECK (evaluated_result IN ('PASS', 'FAIL', 'PENDING_REVIEW')),
    CONSTRAINT ck_inspections_final CHECK (final_result IN ('PASS', 'FAIL')),
    CONSTRAINT ck_inspections_disposition CHECK (disposition IN
        ('ACCEPT', 'REJECT', 'HOLD', 'REWORK', 'CONDITIONAL_ACCEPTANCE')),
    -- Accepted material can only come from a passing inspection.
    CONSTRAINT ck_inspections_no_silent_release CHECK (disposition <> 'ACCEPT' OR final_result = 'PASS')
);
CREATE UNIQUE INDEX uq_inspections_active_item ON inspections (tenant_id, receipt_item_id) WHERE status <> 'CANCELLED';
CREATE INDEX ix_inspections_status ON inspections (tenant_id, status);
CREATE INDEX ix_inspections_supplier ON inspections (tenant_id, supplier_id);
CREATE INDEX ix_inspections_batch ON inspections (tenant_id, batch_number);
SELECT apply_tenant_standards('inspections');

CREATE TABLE inspection_results (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    inspection_id UUID NOT NULL,
    parameter_code TEXT NOT NULL,
    sample_number INT NOT NULL,
    value_numeric NUMERIC(18, 6),
    value_text TEXT,
    value_boolean BOOLEAN,
    value_date DATE,
    evaluation TEXT NOT NULL,
    remarks TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_inspection_results_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_inspection_results_sample UNIQUE (tenant_id, inspection_id, parameter_code, sample_number),
    CONSTRAINT fk_inspection_results_inspection FOREIGN KEY (tenant_id, inspection_id) REFERENCES inspections (tenant_id, id),
    CONSTRAINT ck_inspection_results_evaluation CHECK (evaluation IN ('PASS', 'FAIL', 'PENDING_REVIEW')),
    CONSTRAINT ck_inspection_results_sample CHECK (sample_number > 0)
);
SELECT apply_tenant_standards('inspection_results');

-- Every decision on an inspection, in order. Rows are only ever added.
CREATE TABLE inspection_approvals (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    inspection_id UUID NOT NULL,
    decision TEXT NOT NULL,
    note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_inspection_approvals_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_inspection_approvals_inspection FOREIGN KEY (tenant_id, inspection_id) REFERENCES inspections (tenant_id, id)
);
CREATE INDEX ix_inspection_approvals_inspection ON inspection_approvals (tenant_id, inspection_id);
SELECT apply_tenant_standards('inspection_approvals');

CREATE FUNCTION protect_published_version() RETURNS trigger
    LANGUAGE plpgsql AS
$$
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
CREATE TRIGGER trg_protect_version BEFORE UPDATE OR DELETE ON inspection_template_versions
    FOR EACH ROW EXECUTE FUNCTION protect_published_version();

CREATE FUNCTION protect_published_parameters() RETURNS trigger
    LANGUAGE plpgsql AS
$$
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
CREATE TRIGGER trg_protect_parameters BEFORE INSERT OR UPDATE OR DELETE ON inspection_parameters
    FOR EACH ROW EXECUTE FUNCTION protect_published_parameters();

CREATE FUNCTION protect_inspection_specification() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.spec_snapshot IS DISTINCT FROM OLD.spec_snapshot
        OR NEW.template_version_id IS DISTINCT FROM OLD.template_version_id
        OR NEW.sample_size IS DISTINCT FROM OLD.sample_size THEN
        RAISE EXCEPTION 'The specification of an inspection cannot be changed';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_protect_specification BEFORE UPDATE ON inspections
    FOR EACH ROW EXECUTE FUNCTION protect_inspection_specification();

CREATE FUNCTION protect_submitted_results() RETURNS trigger
    LANGUAGE plpgsql AS
$$
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
CREATE TRIGGER trg_protect_results BEFORE INSERT OR UPDATE OR DELETE ON inspection_results
    FOR EACH ROW EXECUTE FUNCTION protect_submitted_results();

CREATE FUNCTION reject_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'This record cannot be changed';
END;
$$;
CREATE TRIGGER trg_append_only BEFORE UPDATE OR DELETE ON inspection_approvals
    FOR EACH ROW EXECUTE FUNCTION reject_change();
