-- Manufacturing foundation: settings, work centres, machines, BOMs, recipes and routings.
-- See docs/MANUFACTURING.md.

-- How the organisation manufactures. One row per organisation, written on its first save; until then the defaults hold.
CREATE TABLE manufacturing_settings (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    manufacturing_model text DEFAULT 'DISCRETE' NOT NULL,
    -- The person who submits a BOM, recipe or routing cannot approve it.
    separate_approver boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT manufacturing_settings_pkey PRIMARY KEY (id),
    CONSTRAINT uq_manufacturing_settings_tenant UNIQUE (tenant_id),
    CONSTRAINT ck_manufacturing_settings_model CHECK (manufacturing_model IN ('DISCRETE', 'PROCESS', 'HYBRID')),
    CONSTRAINT manufacturing_settings_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id)
);

-- How one product is made. Without a row, the organisation's model decides; a hybrid organisation must choose.
CREATE TABLE product_manufacturing_settings (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    product_id uuid NOT NULL,
    manufacturing_model text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT product_manufacturing_settings_pkey PRIMARY KEY (id),
    CONSTRAINT uq_product_manufacturing_settings UNIQUE (tenant_id, product_id),
    CONSTRAINT ck_product_manufacturing_model CHECK (manufacturing_model IN ('DISCRETE', 'PROCESS')),
    CONSTRAINT product_manufacturing_settings_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_product_manufacturing_product FOREIGN KEY (tenant_id, product_id) REFERENCES products(tenant_id, id)
);

CREATE TABLE work_centers (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    facility_id uuid NOT NULL,
    production_line_id uuid,
    code text NOT NULL,
    name text NOT NULL,
    description text,
    archived_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT work_centers_pkey PRIMARY KEY (id),
    CONSTRAINT uq_work_centers_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_work_centers_code UNIQUE (tenant_id, code),
    CONSTRAINT work_centers_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_work_centers_facility FOREIGN KEY (tenant_id, facility_id) REFERENCES facilities(tenant_id, id),
    CONSTRAINT fk_work_centers_line FOREIGN KEY (tenant_id, production_line_id)
        REFERENCES production_lines(tenant_id, id)
);

CREATE INDEX ix_work_centers_facility ON work_centers (tenant_id, facility_id);

CREATE TABLE machines (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    work_center_id uuid NOT NULL,
    code text NOT NULL,
    name text NOT NULL,
    manufacturer text,
    model text,
    serial_number text,
    status text DEFAULT 'AVAILABLE' NOT NULL,
    status_reason text,
    status_changed_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT machines_pkey PRIMARY KEY (id),
    CONSTRAINT uq_machines_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_machines_code UNIQUE (tenant_id, code),
    CONSTRAINT ck_machines_status
        CHECK (status IN ('AVAILABLE', 'RUNNING', 'IDLE', 'MAINTENANCE', 'BREAKDOWN', 'INACTIVE')),
    CONSTRAINT ck_machines_reason
        CHECK (status NOT IN ('MAINTENANCE', 'BREAKDOWN', 'INACTIVE') OR status_reason IS NOT NULL),
    CONSTRAINT machines_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_machines_work_center FOREIGN KEY (tenant_id, work_center_id) REFERENCES work_centers(tenant_id, id)
);

CREATE INDEX ix_machines_work_center ON machines (tenant_id, work_center_id);

-- Every status a machine has had, for downtime and OEE. Never changed or removed.
CREATE TABLE machine_status_events (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    machine_id uuid NOT NULL,
    from_status text,
    to_status text NOT NULL,
    reason text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT machine_status_events_pkey PRIMARY KEY (id),
    CONSTRAINT machine_status_events_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_machine_status_events_machine FOREIGN KEY (tenant_id, machine_id) REFERENCES machines(tenant_id, id)
);

CREATE INDEX ix_machine_status_events_machine ON machine_status_events (tenant_id, machine_id, created_at);

-- BOMs (discrete) and recipes (process) share one structure. A recipe's base quantity is its batch size.
CREATE TABLE boms (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    kind text NOT NULL,
    product_id uuid NOT NULL,
    revision integer NOT NULL,
    status text DEFAULT 'DRAFT' NOT NULL,
    -- How much of the product, in its own unit, the lines make.
    base_quantity numeric(18,6) DEFAULT 1 NOT NULL,
    change_note text,
    review_note text,
    effective_from date,
    effective_to date,
    submitted_by uuid,
    submitted_at timestamp with time zone,
    approved_by uuid,
    approved_at timestamp with time zone,
    obsoleted_at timestamp with time zone,
    snapshot jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT boms_pkey PRIMARY KEY (id),
    CONSTRAINT uq_boms_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_boms_revision UNIQUE (tenant_id, product_id, kind, revision),
    CONSTRAINT ck_boms_kind CHECK (kind IN ('BOM', 'RECIPE')),
    CONSTRAINT ck_boms_status CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'OBSOLETE')),
    CONSTRAINT ck_boms_revision CHECK (revision > 0),
    CONSTRAINT ck_boms_base_quantity CHECK (base_quantity > 0),
    CONSTRAINT ck_boms_submitted CHECK (status = 'DRAFT' OR (submitted_by IS NOT NULL AND submitted_at IS NOT NULL)),
    CONSTRAINT ck_boms_approved CHECK (status NOT IN ('APPROVED', 'OBSOLETE')
        OR (approved_by IS NOT NULL AND approved_at IS NOT NULL AND effective_from IS NOT NULL AND snapshot IS NOT NULL)),
    CONSTRAINT ck_boms_effective CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT boms_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_boms_product FOREIGN KEY (tenant_id, product_id) REFERENCES products(tenant_id, id)
);

-- One revision in progress per product and kind.
CREATE UNIQUE INDEX uq_boms_open ON boms (tenant_id, product_id, kind) WHERE status IN ('DRAFT', 'SUBMITTED');
CREATE INDEX ix_boms_product ON boms (tenant_id, product_id, kind, status);

CREATE TABLE bom_lines (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    bom_id uuid NOT NULL,
    line_number integer NOT NULL,
    material_id uuid,
    component_product_id uuid,
    quantity numeric(18,6) NOT NULL,
    uom_id uuid NOT NULL,
    scrap_percent numeric(5,2) DEFAULT 0 NOT NULL,
    -- False for a quantity that stays the same whatever the batch size, such as a catalyst charge.
    scales boolean DEFAULT true NOT NULL,
    notes text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT bom_lines_pkey PRIMARY KEY (id),
    CONSTRAINT uq_bom_lines_number UNIQUE (tenant_id, bom_id, line_number),
    CONSTRAINT ck_bom_lines_component CHECK ((material_id IS NULL) <> (component_product_id IS NULL)),
    CONSTRAINT ck_bom_lines_quantity CHECK (quantity > 0),
    CONSTRAINT ck_bom_lines_scrap CHECK (scrap_percent >= 0 AND scrap_percent < 100),
    CONSTRAINT bom_lines_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_bom_lines_bom FOREIGN KEY (tenant_id, bom_id) REFERENCES boms(tenant_id, id),
    CONSTRAINT fk_bom_lines_material FOREIGN KEY (tenant_id, material_id) REFERENCES materials(tenant_id, id),
    CONSTRAINT fk_bom_lines_product FOREIGN KEY (tenant_id, component_product_id) REFERENCES products(tenant_id, id),
    CONSTRAINT fk_bom_lines_uom FOREIGN KEY (uom_id) REFERENCES units_of_measure(id)
);

CREATE INDEX ix_bom_lines_component ON bom_lines (tenant_id, component_product_id)
    WHERE component_product_id IS NOT NULL;

CREATE TABLE routings (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    product_id uuid NOT NULL,
    revision integer NOT NULL,
    status text DEFAULT 'DRAFT' NOT NULL,
    change_note text,
    review_note text,
    effective_from date,
    effective_to date,
    submitted_by uuid,
    submitted_at timestamp with time zone,
    approved_by uuid,
    approved_at timestamp with time zone,
    obsoleted_at timestamp with time zone,
    snapshot jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT routings_pkey PRIMARY KEY (id),
    CONSTRAINT uq_routings_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_routings_revision UNIQUE (tenant_id, product_id, revision),
    CONSTRAINT ck_routings_status CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'OBSOLETE')),
    CONSTRAINT ck_routings_revision CHECK (revision > 0),
    CONSTRAINT ck_routings_submitted
        CHECK (status = 'DRAFT' OR (submitted_by IS NOT NULL AND submitted_at IS NOT NULL)),
    CONSTRAINT ck_routings_approved CHECK (status NOT IN ('APPROVED', 'OBSOLETE')
        OR (approved_by IS NOT NULL AND approved_at IS NOT NULL AND effective_from IS NOT NULL AND snapshot IS NOT NULL)),
    CONSTRAINT ck_routings_effective CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT routings_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_routings_product FOREIGN KEY (tenant_id, product_id) REFERENCES products(tenant_id, id)
);

CREATE UNIQUE INDEX uq_routings_open ON routings (tenant_id, product_id) WHERE status IN ('DRAFT', 'SUBMITTED');
CREATE INDEX ix_routings_product ON routings (tenant_id, product_id, status);

CREATE TABLE routing_operations (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    routing_id uuid NOT NULL,
    sequence integer NOT NULL,
    name text NOT NULL,
    work_center_id uuid NOT NULL,
    setup_minutes numeric(10,2) DEFAULT 0 NOT NULL,
    -- Per unit of the product, in its own unit.
    run_minutes numeric(12,4) DEFAULT 0 NOT NULL,
    instructions text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT routing_operations_pkey PRIMARY KEY (id),
    CONSTRAINT uq_routing_operations_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_routing_operations_sequence UNIQUE (tenant_id, routing_id, sequence),
    CONSTRAINT ck_routing_operations_sequence CHECK (sequence > 0),
    CONSTRAINT ck_routing_operations_times CHECK (setup_minutes >= 0 AND run_minutes >= 0),
    CONSTRAINT routing_operations_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_routing_operations_routing FOREIGN KEY (tenant_id, routing_id) REFERENCES routings(tenant_id, id),
    CONSTRAINT fk_routing_operations_work_center
        FOREIGN KEY (tenant_id, work_center_id) REFERENCES work_centers(tenant_id, id)
);

CREATE INDEX ix_routing_operations_work_center ON routing_operations (tenant_id, work_center_id);

-- What a recipe or a routing operation must hold, such as a temperature. Belongs to exactly one of them.
CREATE TABLE process_parameters (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    bom_id uuid,
    operation_id uuid,
    code text NOT NULL,
    name text NOT NULL,
    unit text,
    target_value numeric(18,6),
    min_value numeric(18,6),
    max_value numeric(18,6),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT process_parameters_pkey PRIMARY KEY (id),
    CONSTRAINT ck_process_parameters_owner CHECK ((bom_id IS NULL) <> (operation_id IS NULL)),
    CONSTRAINT ck_process_parameters_limits CHECK (
        (min_value IS NULL OR max_value IS NULL OR min_value <= max_value)
        AND (target_value IS NULL OR min_value IS NULL OR target_value >= min_value)
        AND (target_value IS NULL OR max_value IS NULL OR target_value <= max_value)),
    CONSTRAINT ck_process_parameters_value
        CHECK (target_value IS NOT NULL OR min_value IS NOT NULL OR max_value IS NOT NULL),
    CONSTRAINT process_parameters_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_process_parameters_bom FOREIGN KEY (tenant_id, bom_id) REFERENCES boms(tenant_id, id),
    CONSTRAINT fk_process_parameters_operation
        FOREIGN KEY (tenant_id, operation_id) REFERENCES routing_operations(tenant_id, id)
);

CREATE UNIQUE INDEX uq_process_parameters_bom ON process_parameters (tenant_id, bom_id, code) WHERE bom_id IS NOT NULL;
CREATE UNIQUE INDEX uq_process_parameters_operation ON process_parameters (tenant_id, operation_id, code)
    WHERE operation_id IS NOT NULL;

SELECT apply_tenant_standards('manufacturing_settings');
SELECT apply_tenant_standards('product_manufacturing_settings');
SELECT apply_tenant_standards('work_centers');
SELECT apply_tenant_standards('machines');
SELECT apply_tenant_standards('machine_status_events');
SELECT apply_tenant_standards('boms');
SELECT apply_tenant_standards('bom_lines');
SELECT apply_tenant_standards('routings');
SELECT apply_tenant_standards('routing_operations');
SELECT apply_tenant_standards('process_parameters');

-- History stays as it was written.
CREATE FUNCTION refuse_history_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION '% % is history and cannot be changed', TG_TABLE_NAME, OLD.id USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER trg_append_only BEFORE UPDATE OR DELETE ON machine_status_events
    FOR EACH ROW EXECUTE FUNCTION refuse_history_change();

-- A revision is only edited, or deleted, while it is a draft. Once approved, only its end date and its retirement may
-- change. An obsolete revision never changes.
CREATE FUNCTION protect_revision() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status <> 'DRAFT' THEN
            RAISE EXCEPTION '% % is not a draft and cannot be deleted', TG_TABLE_NAME, OLD.id
                USING ERRCODE = 'restrict_violation';
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.status = 'OBSOLETE'
        OR (OLD.status = 'APPROVED' AND (
            NEW.status NOT IN ('APPROVED', 'OBSOLETE')
            OR NEW.snapshot IS DISTINCT FROM OLD.snapshot
            OR NEW.effective_from IS DISTINCT FROM OLD.effective_from
            OR NEW.approved_by IS DISTINCT FROM OLD.approved_by
            OR NEW.product_id IS DISTINCT FROM OLD.product_id
            OR NEW.revision IS DISTINCT FROM OLD.revision)) THEN
        RAISE EXCEPTION '% % is % and cannot be changed', TG_TABLE_NAME, OLD.id, OLD.status
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_protect_revision BEFORE UPDATE OR DELETE ON boms
    FOR EACH ROW EXECUTE FUNCTION protect_revision();
CREATE TRIGGER trg_protect_revision BEFORE UPDATE OR DELETE ON routings
    FOR EACH ROW EXECUTE FUNCTION protect_revision();

-- Lines, operations and parameters change only while what they belong to is a draft.
CREATE FUNCTION protect_revision_content() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    row_data record := COALESCE(NEW, OLD);
    owner_status text;
BEGIN
    IF TG_TABLE_NAME = 'bom_lines' THEN
        SELECT status INTO owner_status FROM boms WHERE id = row_data.bom_id;
    ELSIF TG_TABLE_NAME = 'routing_operations' THEN
        SELECT status INTO owner_status FROM routings WHERE id = row_data.routing_id;
    ELSIF row_data.bom_id IS NOT NULL THEN
        SELECT status INTO owner_status FROM boms WHERE id = row_data.bom_id;
    ELSE
        SELECT r.status INTO owner_status
        FROM routing_operations o JOIN routings r ON r.id = o.routing_id
        WHERE o.id = row_data.operation_id;
    END IF;
    IF owner_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION '% belongs to a revision that is not a draft', TG_TABLE_NAME
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN row_data;
END;
$$;

CREATE TRIGGER trg_protect_content BEFORE INSERT OR UPDATE OR DELETE ON bom_lines
    FOR EACH ROW EXECUTE FUNCTION protect_revision_content();
CREATE TRIGGER trg_protect_content BEFORE INSERT OR UPDATE OR DELETE ON routing_operations
    FOR EACH ROW EXECUTE FUNCTION protect_revision_content();
CREATE TRIGGER trg_protect_content BEFORE INSERT OR UPDATE OR DELETE ON process_parameters
    FOR EACH ROW EXECUTE FUNCTION protect_revision_content();

-- Permissions, a Production Manager role, and the plan feature that carries them.
INSERT INTO permissions (code, module, description) VALUES
    ('MANUFACTURING_VIEW', 'Manufacturing', 'View manufacturing settings, work centres, machines, BOMs, recipes and routings'),
    ('MANUFACTURING_MANAGE', 'Manufacturing', 'Change manufacturing settings, work centres and machines'),
    ('BOM_MANAGE', 'Manufacturing', 'Draft and submit bills of materials'),
    ('BOM_APPROVE', 'Manufacturing', 'Approve, send back and retire bills of materials'),
    ('RECIPE_MANAGE', 'Manufacturing', 'Draft and submit recipes'),
    ('RECIPE_APPROVE', 'Manufacturing', 'Approve, send back and retire recipes'),
    ('ROUTING_MANAGE', 'Manufacturing', 'Draft and submit routings'),
    ('ROUTING_APPROVE', 'Manufacturing', 'Approve, send back and retire routings');

ALTER TABLE roles NO FORCE ROW LEVEL SECURITY;
ALTER TABLE role_permissions NO FORCE ROW LEVEL SECURITY;

INSERT INTO roles (id, tenant_id, code, name, description) VALUES
    ('7c4f2a8e-3d1b-4e5a-9f60-2b8d1c7e4a93', NULL, 'PRODUCTION_MANAGER', 'Production Manager',
     'Owns manufacturing definitions, work centres and machines');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r CROSS JOIN permissions p
WHERE r.tenant_id IS NULL
  AND (
    (r.code IN ('ORGANISATION_OWNER', 'ORGANISATION_ADMIN', 'PLANT_MANAGER', 'PRODUCTION_MANAGER')
        AND p.module = 'Manufacturing')
    OR (r.code = 'QUALITY_MANAGER'
        AND p.code IN ('MANUFACTURING_VIEW', 'BOM_APPROVE', 'RECIPE_APPROVE', 'ROUTING_APPROVE'))
    OR (r.code IN ('QUALITY_ENGINEER', 'QUALITY_INSPECTOR', 'AUDITOR', 'VIEWER') AND p.code = 'MANUFACTURING_VIEW')
    OR (r.code = 'PRODUCTION_MANAGER' AND p.code IN (
        'FACILITY_VIEW', 'PRODUCT_VIEW', 'PRODUCT_MANAGE', 'PURCHASE_VIEW', 'TEMPLATE_VIEW', 'INSPECTION_VIEW',
        'NCR_VIEW', 'NCR_CREATE', 'TRACEABILITY_VIEW', 'TRACEABILITY_MANAGE', 'CALIBRATION_VIEW', 'REPORT_VIEW'))
  );

ALTER TABLE roles FORCE ROW LEVEL SECURITY;
ALTER TABLE role_permissions FORCE ROW LEVEL SECURITY;

INSERT INTO feature_definitions (id, code, name, description, value_type, active, default_enabled) VALUES
    (gen_random_uuid(), 'MANUFACTURING', 'Manufacturing',
     'BOMs, recipes, routings, work centres and machines', 'BOOLEAN', true, false);

UPDATE subscription_plans
SET features = array_append(features, 'MANUFACTURING'), version = version + 1
WHERE code IN ('PROFESSIONAL', 'ENTERPRISE') AND NOT ('MANUFACTURING' = ANY (features));

-- The plan history shows the change.
INSERT INTO subscription_plan_versions (id, plan_id, version, snapshot, reason)
SELECT gen_random_uuid(), p.id, p.version,
       latest.snapshot || jsonb_build_object('features', to_jsonb(p.features)),
       'Manufacturing added to the plan'
FROM subscription_plans p
JOIN LATERAL (
    SELECT v.snapshot FROM subscription_plan_versions v WHERE v.plan_id = p.id ORDER BY v.version DESC LIMIT 1
) latest ON true
WHERE p.code IN ('PROFESSIONAL', 'ENTERPRISE')
ON CONFLICT (plan_id, version) DO NOTHING;
