-- Controlled amendment of a received lot, or of the material recorded against a production batch. The lot or
-- genealogy changes only when a correction is applied, and the correction keeps the original values for good.

INSERT INTO permissions (code, module, description) VALUES
    ('MATERIAL_CORRECTION_REQUEST', 'Traceability', 'Request corrections to material records'),
    ('MATERIAL_CORRECTION_APPROVE', 'Traceability', 'Approve corrections to material records');

-- System roles are outside every tenant, so only a migration can extend them. FORCE returns straight after.
ALTER TABLE role_permissions NO FORCE ROW LEVEL SECURITY;
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON
    (p.code = 'MATERIAL_CORRECTION_REQUEST'
        AND r.code IN ('ORGANISATION_OWNER', 'ORGANISATION_ADMIN', 'PLANT_MANAGER', 'QUALITY_MANAGER', 'QUALITY_ENGINEER'))
    OR (p.code = 'MATERIAL_CORRECTION_APPROVE'
        AND r.code IN ('ORGANISATION_OWNER', 'PLANT_MANAGER', 'QUALITY_MANAGER'));
ALTER TABLE role_permissions FORCE ROW LEVEL SECURITY;

CREATE TABLE material_corrections (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    correction_number TEXT NOT NULL,
    target_type TEXT NOT NULL,
    receipt_item_id UUID,
    batch_relationship_id UUID,
    production_batch_id UUID,
    status TEXT NOT NULL DEFAULT 'DRAFT',
    reason TEXT NOT NULL,
    additional_approval BOOLEAN NOT NULL DEFAULT false,
    submitted_at TIMESTAMPTZ,
    decision_note TEXT,
    decided_at TIMESTAMPTZ,
    applied_at TIMESTAMPTZ,
    replacement_relationship_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_material_corrections_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_material_corrections_number UNIQUE (tenant_id, correction_number),
    CONSTRAINT fk_material_corrections_lot FOREIGN KEY (tenant_id, receipt_item_id) REFERENCES material_receipt_items (tenant_id, id),
    CONSTRAINT fk_material_corrections_relationship FOREIGN KEY (tenant_id, batch_relationship_id) REFERENCES batch_relationships (tenant_id, id),
    CONSTRAINT fk_material_corrections_batch FOREIGN KEY (tenant_id, production_batch_id) REFERENCES production_batches (tenant_id, id),
    CONSTRAINT fk_material_corrections_replacement FOREIGN KEY (tenant_id, replacement_relationship_id) REFERENCES batch_relationships (tenant_id, id),
    CONSTRAINT ck_material_corrections_type CHECK (target_type IN ('LOT', 'CONSUMPTION')),
    CONSTRAINT ck_material_corrections_status CHECK (status IN
        ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'APPLIED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_material_corrections_target CHECK (
        (target_type = 'LOT' AND receipt_item_id IS NOT NULL AND batch_relationship_id IS NULL)
        OR (target_type = 'CONSUMPTION' AND batch_relationship_id IS NOT NULL AND production_batch_id IS NOT NULL))
);
CREATE INDEX ix_material_corrections_lot ON material_corrections (tenant_id, receipt_item_id);
CREATE INDEX ix_material_corrections_batch ON material_corrections (tenant_id, production_batch_id);
-- One correction at a time, so that two cannot be approved against the same original values.
CREATE UNIQUE INDEX uq_material_corrections_open_lot ON material_corrections (tenant_id, receipt_item_id)
    WHERE target_type = 'LOT' AND status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED');
CREATE UNIQUE INDEX uq_material_corrections_open_consumption ON material_corrections (tenant_id, batch_relationship_id)
    WHERE target_type = 'CONSUMPTION' AND status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED');
SELECT apply_tenant_standards('material_corrections');

CREATE TABLE material_correction_items (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    correction_id UUID NOT NULL,
    field_name TEXT NOT NULL,
    original_value TEXT,
    corrected_value TEXT NOT NULL,
    original_label TEXT,
    corrected_label TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_material_correction_items_field UNIQUE (tenant_id, correction_id, field_name),
    CONSTRAINT fk_material_correction_items_correction FOREIGN KEY (tenant_id, correction_id) REFERENCES material_corrections (tenant_id, id),
    CONSTRAINT ck_material_correction_items_field CHECK (field_name IN
        ('MATERIAL', 'SUPPLIER', 'SUPPLIER_BATCH', 'QUANTITY', 'SOURCE_LOT'))
);
SELECT apply_tenant_standards('material_correction_items');

CREATE TABLE material_correction_approvals (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    correction_id UUID NOT NULL,
    approver_id UUID NOT NULL REFERENCES users (id),
    note TEXT,
    impact_acknowledged BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_material_correction_approvals UNIQUE (tenant_id, correction_id, approver_id),
    CONSTRAINT fk_material_correction_approvals_correction FOREIGN KEY (tenant_id, correction_id) REFERENCES material_corrections (tenant_id, id)
);
SELECT apply_tenant_standards('material_correction_approvals');

CREATE TRIGGER trg_append_only BEFORE UPDATE OR DELETE ON material_correction_items
    FOR EACH ROW EXECUTE FUNCTION reject_change();
CREATE TRIGGER trg_append_only BEFORE UPDATE OR DELETE ON material_correction_approvals
    FOR EACH ROW EXECUTE FUNCTION reject_change();

CREATE FUNCTION protect_finished_correction() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.status IN ('APPLIED', 'REJECTED', 'CANCELLED') THEN
        RAISE EXCEPTION '% % cannot be changed', TG_TABLE_NAME, OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$;
CREATE TRIGGER trg_protect_finished BEFORE UPDATE OR DELETE ON material_corrections
    FOR EACH ROW EXECUTE FUNCTION protect_finished_correction();
