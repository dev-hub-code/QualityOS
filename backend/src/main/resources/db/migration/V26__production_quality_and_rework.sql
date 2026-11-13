-- Manufacturing phase 5: quality checkpoints on work orders, quality holds, and rework orders.
-- The inspections themselves are the existing ones; nothing here records a result. See docs/MANUFACTURING.md.

-- Where an inspection is required in production. Configuration: changing one never touches released orders.
CREATE TABLE quality_checkpoints (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    name text NOT NULL,
    stage text NOT NULL,
    template_id uuid NOT NULL,
    -- Each narrows where it applies; left empty, it applies everywhere.
    product_id uuid,
    work_center_id uuid,
    operation_sequence integer,
    mandatory boolean DEFAULT true NOT NULL,
    archived_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT quality_checkpoints_pkey PRIMARY KEY (id),
    CONSTRAINT uq_quality_checkpoints_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_quality_checkpoints_stage
        CHECK (stage IN ('OPERATION_START', 'IN_PROCESS', 'OPERATION_COMPLETION', 'FINAL_RELEASE')),
    CONSTRAINT ck_quality_checkpoints_final
        CHECK (stage <> 'FINAL_RELEASE' OR (work_center_id IS NULL AND operation_sequence IS NULL)),
    CONSTRAINT ck_quality_checkpoints_sequence CHECK (operation_sequence IS NULL OR operation_sequence > 0),
    CONSTRAINT quality_checkpoints_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_quality_checkpoints_template
        FOREIGN KEY (tenant_id, template_id) REFERENCES inspection_templates(tenant_id, id),
    CONSTRAINT fk_quality_checkpoints_product FOREIGN KEY (tenant_id, product_id) REFERENCES products(tenant_id, id),
    CONSTRAINT fk_quality_checkpoints_work_center
        FOREIGN KEY (tenant_id, work_center_id) REFERENCES work_centers(tenant_id, id)
);

-- The checkpoints a work order took on release, frozen with it. Never changed or removed.
CREATE TABLE work_order_quality_gates (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    work_order_id uuid NOT NULL,
    -- Null for the final release of the order's batch.
    operation_id uuid,
    checkpoint_id uuid NOT NULL,
    name text NOT NULL,
    stage text NOT NULL,
    template_id uuid NOT NULL,
    mandatory boolean NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT work_order_quality_gates_pkey PRIMARY KEY (id),
    CONSTRAINT uq_work_order_quality_gates_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_work_order_quality_gates_checkpoint UNIQUE NULLS NOT DISTINCT (tenant_id, work_order_id, checkpoint_id, operation_id),
    CONSTRAINT ck_work_order_quality_gates_stage CHECK ((stage = 'FINAL_RELEASE') = (operation_id IS NULL)),
    CONSTRAINT work_order_quality_gates_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_work_order_quality_gates_order FOREIGN KEY (tenant_id, work_order_id) REFERENCES work_orders(tenant_id, id),
    CONSTRAINT fk_work_order_quality_gates_operation
        FOREIGN KEY (tenant_id, operation_id) REFERENCES work_order_operations(tenant_id, id),
    CONSTRAINT fk_work_order_quality_gates_checkpoint
        FOREIGN KEY (tenant_id, checkpoint_id) REFERENCES quality_checkpoints(tenant_id, id),
    CONSTRAINT fk_work_order_quality_gates_template
        FOREIGN KEY (tenant_id, template_id) REFERENCES inspection_templates(tenant_id, id)
);

CREATE INDEX ix_work_order_quality_gates_order ON work_order_quality_gates (tenant_id, work_order_id);

-- Every inspection started for a gate. The latest decides where the gate stands; earlier ones stay as evidence.
CREATE TABLE quality_gate_inspections (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    gate_id uuid NOT NULL,
    inspection_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT quality_gate_inspections_pkey PRIMARY KEY (id),
    CONSTRAINT uq_quality_gate_inspections_inspection UNIQUE (tenant_id, inspection_id),
    CONSTRAINT quality_gate_inspections_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_quality_gate_inspections_gate
        FOREIGN KEY (tenant_id, gate_id) REFERENCES work_order_quality_gates(tenant_id, id),
    CONSTRAINT fk_quality_gate_inspections_inspection
        FOREIGN KEY (tenant_id, inspection_id) REFERENCES inspections(tenant_id, id)
);

CREATE INDEX ix_quality_gate_inspections_gate ON quality_gate_inspections (tenant_id, gate_id, created_at);

-- Rework of rejected production, on the batch it came from, so its genealogy stays as it was.
CREATE TABLE rework_orders (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    rework_number text NOT NULL,
    work_order_id uuid NOT NULL,
    production_batch_id uuid NOT NULL,
    operation_id uuid,
    ncr_id uuid,
    source_inspection_id uuid,
    -- In the work order's unit.
    quantity numeric(18,6) NOT NULL,
    uom_id uuid NOT NULL,
    reason text NOT NULL,
    instructions text,
    status text DEFAULT 'OPEN' NOT NULL,
    status_reason text,
    approved_by uuid,
    approved_at timestamp with time zone,
    started_at timestamp with time zone,
    completed_at timestamp with time zone,
    reworked_quantity numeric(18,6),
    scrapped_quantity numeric(18,6),
    reinspection_id uuid,
    final_disposition text,
    closed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT rework_orders_pkey PRIMARY KEY (id),
    CONSTRAINT uq_rework_orders_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_rework_orders_number UNIQUE (tenant_id, rework_number),
    CONSTRAINT ck_rework_orders_status
        CHECK (status IN ('OPEN', 'APPROVED', 'IN_PROGRESS', 'AWAITING_INSPECTION', 'CLOSED', 'CANCELLED')),
    CONSTRAINT ck_rework_orders_quantity CHECK (quantity > 0),
    CONSTRAINT ck_rework_orders_outcome CHECK (
        (reworked_quantity IS NULL AND scrapped_quantity IS NULL)
        OR (reworked_quantity >= 0 AND scrapped_quantity >= 0 AND reworked_quantity + scrapped_quantity = quantity)),
    CONSTRAINT ck_rework_orders_disposition CHECK (final_disposition IS NULL
        OR final_disposition IN ('ACCEPT', 'CONDITIONAL_ACCEPTANCE', 'REJECT', 'REWORK')),
    CONSTRAINT ck_rework_orders_closed CHECK ((status = 'CLOSED') = (final_disposition IS NOT NULL)),
    CONSTRAINT rework_orders_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_rework_orders_order FOREIGN KEY (tenant_id, work_order_id) REFERENCES work_orders(tenant_id, id),
    CONSTRAINT fk_rework_orders_batch
        FOREIGN KEY (tenant_id, production_batch_id) REFERENCES production_batches(tenant_id, id),
    CONSTRAINT fk_rework_orders_operation
        FOREIGN KEY (tenant_id, operation_id) REFERENCES work_order_operations(tenant_id, id),
    CONSTRAINT fk_rework_orders_ncr FOREIGN KEY (tenant_id, ncr_id) REFERENCES non_conformances(tenant_id, id),
    CONSTRAINT fk_rework_orders_source_inspection
        FOREIGN KEY (tenant_id, source_inspection_id) REFERENCES inspections(tenant_id, id),
    CONSTRAINT fk_rework_orders_reinspection
        FOREIGN KEY (tenant_id, reinspection_id) REFERENCES inspections(tenant_id, id),
    CONSTRAINT fk_rework_orders_uom FOREIGN KEY (uom_id) REFERENCES units_of_measure(id)
);

CREATE INDEX ix_rework_orders_order ON rework_orders (tenant_id, work_order_id);
CREATE INDEX ix_rework_orders_status ON rework_orders (tenant_id, status);

-- What the rework involves, step by step.
CREATE TABLE rework_order_steps (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    rework_order_id uuid NOT NULL,
    sequence integer NOT NULL,
    description text NOT NULL,
    work_center_id uuid,
    completed_by uuid,
    completed_at timestamp with time zone,
    note text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT rework_order_steps_pkey PRIMARY KEY (id),
    CONSTRAINT uq_rework_order_steps_sequence UNIQUE (tenant_id, rework_order_id, sequence),
    CONSTRAINT ck_rework_order_steps_done CHECK ((completed_by IS NULL) = (completed_at IS NULL)),
    CONSTRAINT rework_order_steps_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_rework_order_steps_order
        FOREIGN KEY (tenant_id, rework_order_id) REFERENCES rework_orders(tenant_id, id),
    CONSTRAINT fk_rework_order_steps_work_center
        FOREIGN KEY (tenant_id, work_center_id) REFERENCES work_centers(tenant_id, id)
);

-- Every status a rework order took, with who and why. Never changed or removed.
CREATE TABLE rework_order_history (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    rework_order_id uuid NOT NULL,
    from_status text,
    to_status text NOT NULL,
    reason text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT rework_order_history_pkey PRIMARY KEY (id),
    CONSTRAINT rework_order_history_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_rework_order_history_order
        FOREIGN KEY (tenant_id, rework_order_id) REFERENCES rework_orders(tenant_id, id)
);

CREATE INDEX ix_rework_order_history_order ON rework_order_history (tenant_id, rework_order_id, created_at);

SELECT apply_tenant_standards('quality_checkpoints');
SELECT apply_tenant_standards('work_order_quality_gates');
SELECT apply_tenant_standards('quality_gate_inspections');
SELECT apply_tenant_standards('rework_orders');
SELECT apply_tenant_standards('rework_order_steps');
SELECT apply_tenant_standards('rework_order_history');

CREATE TRIGGER trg_append_only BEFORE UPDATE OR DELETE ON work_order_quality_gates
    FOR EACH ROW EXECUTE FUNCTION refuse_history_change();
CREATE TRIGGER trg_append_only BEFORE UPDATE OR DELETE ON quality_gate_inspections
    FOR EACH ROW EXECUTE FUNCTION refuse_history_change();
CREATE TRIGGER trg_append_only BEFORE UPDATE OR DELETE ON rework_order_history
    FOR EACH ROW EXECUTE FUNCTION refuse_history_change();

-- A rework order is production history: never deleted, and fixed once closed or cancelled. A step, once done, stays done.
CREATE FUNCTION protect_rework() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION '% % is production history and cannot be deleted', TG_TABLE_NAME, OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF TG_TABLE_NAME = 'rework_orders' THEN
        IF OLD.status IN ('CLOSED', 'CANCELLED')
            OR NEW.work_order_id IS DISTINCT FROM OLD.work_order_id
            OR NEW.production_batch_id IS DISTINCT FROM OLD.production_batch_id
            OR NEW.quantity IS DISTINCT FROM OLD.quantity THEN
            RAISE EXCEPTION 'rework order % cannot be changed this way', OLD.rework_number
                USING ERRCODE = 'restrict_violation';
        END IF;
    ELSIF OLD.completed_at IS NOT NULL OR NEW.rework_order_id IS DISTINCT FROM OLD.rework_order_id THEN
        RAISE EXCEPTION 'rework step % is done and cannot be changed', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_protect_rework BEFORE UPDATE OR DELETE ON rework_orders
    FOR EACH ROW EXECUTE FUNCTION protect_rework();
CREATE TRIGGER trg_protect_rework BEFORE UPDATE OR DELETE ON rework_order_steps
    FOR EACH ROW EXECUTE FUNCTION protect_rework();

INSERT INTO permissions (code, module, description) VALUES
    ('QUALITY_CHECKPOINT_MANAGE', 'Manufacturing', 'Set where production needs inspections'),
    ('REWORK_MANAGE', 'Manufacturing', 'Raise, cancel and close rework orders'),
    ('REWORK_APPROVE', 'Manufacturing', 'Authorise rework orders');

ALTER TABLE roles NO FORCE ROW LEVEL SECURITY;
ALTER TABLE role_permissions NO FORCE ROW LEVEL SECURITY;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r CROSS JOIN permissions p
WHERE r.tenant_id IS NULL
  AND (
    (r.code IN ('ORGANISATION_OWNER', 'ORGANISATION_ADMIN', 'PLANT_MANAGER')
        AND p.code IN ('QUALITY_CHECKPOINT_MANAGE', 'REWORK_MANAGE', 'REWORK_APPROVE'))
    OR (r.code = 'PRODUCTION_MANAGER' AND p.code IN ('REWORK_MANAGE', 'REWORK_APPROVE'))
    OR (r.code = 'QUALITY_MANAGER' AND p.code IN ('QUALITY_CHECKPOINT_MANAGE', 'REWORK_APPROVE'))
    OR (r.code = 'QUALITY_ENGINEER' AND p.code = 'REWORK_MANAGE')
  );

ALTER TABLE roles FORCE ROW LEVEL SECURITY;
ALTER TABLE role_permissions FORCE ROW LEVEL SECURITY;

UPDATE feature_definitions
SET description = 'BOMs, recipes, routings, work centres, machines, production plans, work orders, shop-floor execution, quality checkpoints and rework'
WHERE code = 'MANUFACTURING';
