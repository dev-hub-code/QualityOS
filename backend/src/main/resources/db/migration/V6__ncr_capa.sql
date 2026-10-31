-- Non-conformances and corrective actions. Closed records and every status history are protected here as well as
-- in the application, because they are the evidence an auditor reads.

CREATE TABLE defect_categories (
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
    CONSTRAINT uq_defect_categories_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_defect_categories_code UNIQUE (tenant_id, code)
);
SELECT apply_tenant_standards('defect_categories');

CREATE TABLE non_conformances (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    ncr_number TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'OPEN',
    source TEXT NOT NULL,
    inspection_id UUID,
    supplier_id UUID,
    material_id UUID,
    batch_number TEXT,
    defect_category_id UUID,
    severity TEXT NOT NULL,
    title TEXT NOT NULL,
    description TEXT NOT NULL,
    rejected_quantity NUMERIC(18, 6),
    assigned_to UUID REFERENCES users (id),
    containment_action TEXT,
    root_cause TEXT,
    verification_requested_by UUID REFERENCES users (id),
    outcome_note TEXT,
    closed_at TIMESTAMPTZ,
    closed_by UUID REFERENCES users (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_non_conformances_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_non_conformances_number UNIQUE (tenant_id, ncr_number),
    CONSTRAINT fk_ncr_inspection FOREIGN KEY (tenant_id, inspection_id) REFERENCES inspections (tenant_id, id),
    CONSTRAINT fk_ncr_supplier FOREIGN KEY (tenant_id, supplier_id) REFERENCES suppliers (tenant_id, id),
    CONSTRAINT fk_ncr_material FOREIGN KEY (tenant_id, material_id) REFERENCES materials (tenant_id, id),
    CONSTRAINT fk_ncr_defect FOREIGN KEY (tenant_id, defect_category_id) REFERENCES defect_categories (tenant_id, id),
    CONSTRAINT ck_ncr_status CHECK (status IN ('OPEN', 'UNDER_INVESTIGATION', 'CONTAINMENT', 'ACTION_REQUIRED',
        'PENDING_VERIFICATION', 'CLOSED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_ncr_source CHECK (source IN ('INSPECTION', 'MANUAL')),
    CONSTRAINT ck_ncr_severity CHECK (severity IN ('CRITICAL', 'MAJOR', 'MINOR')),
    CONSTRAINT ck_ncr_quantity CHECK (rejected_quantity IS NULL OR rejected_quantity >= 0),
    CONSTRAINT ck_ncr_inspection_source CHECK ((source = 'INSPECTION') = (inspection_id IS NOT NULL)),
    CONSTRAINT ck_ncr_closed CHECK (status <> 'CLOSED' OR (closed_at IS NOT NULL AND closed_by IS NOT NULL
        AND root_cause IS NOT NULL AND containment_action IS NOT NULL))
);
CREATE UNIQUE INDEX uq_ncr_per_inspection ON non_conformances (tenant_id, inspection_id)
    WHERE inspection_id IS NOT NULL;
CREATE INDEX ix_ncr_status ON non_conformances (tenant_id, status);
SELECT apply_tenant_standards('non_conformances');

CREATE TABLE ncr_status_history (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    ncr_id UUID NOT NULL,
    from_status TEXT,
    to_status TEXT NOT NULL,
    note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_ncr_history FOREIGN KEY (tenant_id, ncr_id) REFERENCES non_conformances (tenant_id, id)
);
CREATE INDEX ix_ncr_history ON ncr_status_history (tenant_id, ncr_id, created_at);
SELECT apply_tenant_standards('ncr_status_history');

CREATE TABLE corrective_actions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    capa_number TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'OPEN',
    ncr_id UUID,
    title TEXT NOT NULL,
    problem_statement TEXT NOT NULL,
    root_cause TEXT,
    five_whys JSONB NOT NULL DEFAULT '[]',
    fishbone JSONB NOT NULL DEFAULT '{}',
    owner_id UUID NOT NULL REFERENCES users (id),
    target_date DATE NOT NULL,
    effectiveness_required BOOLEAN NOT NULL DEFAULT TRUE,
    overdue_notified_at TIMESTAMPTZ,
    outcome_note TEXT,
    closed_at TIMESTAMPTZ,
    closed_by UUID REFERENCES users (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_corrective_actions_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_corrective_actions_number UNIQUE (tenant_id, capa_number),
    CONSTRAINT fk_capa_ncr FOREIGN KEY (tenant_id, ncr_id) REFERENCES non_conformances (tenant_id, id),
    CONSTRAINT ck_capa_status CHECK (status IN ('OPEN', 'ROOT_CAUSE_ANALYSIS', 'ACTION_PLANNING', 'IMPLEMENTATION',
        'EFFECTIVENESS_VERIFICATION', 'CLOSED', 'CANCELLED')),
    CONSTRAINT ck_capa_closed CHECK (status <> 'CLOSED' OR (closed_at IS NOT NULL AND closed_by IS NOT NULL
        AND root_cause IS NOT NULL))
);
CREATE INDEX ix_capa_status ON corrective_actions (tenant_id, status);
CREATE INDEX ix_capa_ncr ON corrective_actions (tenant_id, ncr_id);
CREATE INDEX ix_capa_overdue ON corrective_actions (target_date)
    WHERE status NOT IN ('CLOSED', 'CANCELLED');
SELECT apply_tenant_standards('corrective_actions');

CREATE TABLE capa_actions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    capa_id UUID NOT NULL,
    action_type TEXT NOT NULL,
    description TEXT NOT NULL,
    owner_id UUID NOT NULL REFERENCES users (id),
    target_date DATE NOT NULL,
    completed_at TIMESTAMPTZ,
    completion_note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_capa_actions_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_capa_action FOREIGN KEY (tenant_id, capa_id) REFERENCES corrective_actions (tenant_id, id),
    CONSTRAINT ck_capa_action_type CHECK (action_type IN ('CORRECTIVE', 'PREVENTIVE')),
    CONSTRAINT ck_capa_action_done CHECK ((completed_at IS NULL) = (completion_note IS NULL))
);
CREATE INDEX ix_capa_actions ON capa_actions (tenant_id, capa_id);
SELECT apply_tenant_standards('capa_actions');

CREATE TABLE capa_verifications (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    capa_id UUID NOT NULL,
    effective BOOLEAN NOT NULL,
    method TEXT NOT NULL,
    note TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_capa_verification FOREIGN KEY (tenant_id, capa_id) REFERENCES corrective_actions (tenant_id, id)
);
CREATE INDEX ix_capa_verifications ON capa_verifications (tenant_id, capa_id, created_at);
SELECT apply_tenant_standards('capa_verifications');

CREATE TABLE capa_status_history (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    capa_id UUID NOT NULL,
    from_status TEXT,
    to_status TEXT NOT NULL,
    note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_capa_history FOREIGN KEY (tenant_id, capa_id) REFERENCES corrective_actions (tenant_id, id)
);
CREATE INDEX ix_capa_history ON capa_status_history (tenant_id, capa_id, created_at);
SELECT apply_tenant_standards('capa_status_history');

CREATE TRIGGER trg_append_only BEFORE UPDATE OR DELETE ON ncr_status_history
    FOR EACH ROW EXECUTE FUNCTION reject_change();
CREATE TRIGGER trg_append_only BEFORE UPDATE OR DELETE ON capa_status_history
    FOR EACH ROW EXECUTE FUNCTION reject_change();
CREATE TRIGGER trg_append_only BEFORE UPDATE OR DELETE ON capa_verifications
    FOR EACH ROW EXECUTE FUNCTION reject_change();

CREATE FUNCTION protect_finished_record() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.status IN ('CLOSED', 'REJECTED', 'CANCELLED') THEN
        RAISE EXCEPTION '% % cannot be changed', TG_TABLE_NAME, OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$;
CREATE TRIGGER trg_protect_finished BEFORE UPDATE OR DELETE ON non_conformances
    FOR EACH ROW EXECUTE FUNCTION protect_finished_record();
CREATE TRIGGER trg_protect_finished BEFORE UPDATE OR DELETE ON corrective_actions
    FOR EACH ROW EXECUTE FUNCTION protect_finished_record();

-- Tells the overdue job which tenants have work, as tenants_with_pending_email() does for mail: ids only, with the
-- policy limited to this schema's owner.
DO $$
BEGIN
    EXECUTE format('CREATE POLICY overdue_scan ON corrective_actions FOR SELECT TO %I USING (true)', current_user);
END;
$$;

CREATE FUNCTION tenants_with_overdue_capas() RETURNS SETOF UUID
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS
$$
    SELECT DISTINCT tenant_id FROM corrective_actions
    WHERE status NOT IN ('CLOSED', 'CANCELLED') AND target_date < now()::date + 1;
$$;
REVOKE ALL ON FUNCTION tenants_with_overdue_capas() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenants_with_overdue_capas() TO ${approle};
