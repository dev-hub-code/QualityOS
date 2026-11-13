-- Manufacturing phase 2: production plans and work orders. See docs/MANUFACTURING.md.

-- Whether releasing a work order needs an approved BOM or recipe, and an approved routing.
ALTER TABLE manufacturing_settings
    ADD COLUMN work_order_requires_bom boolean DEFAULT true NOT NULL,
    ADD COLUMN work_order_requires_routing boolean DEFAULT false NOT NULL;

CREATE TABLE production_plans (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    plan_number text NOT NULL,
    name text NOT NULL,
    facility_id uuid NOT NULL,
    period_start date NOT NULL,
    period_end date NOT NULL,
    status text DEFAULT 'DRAFT' NOT NULL,
    notes text,
    approved_by uuid,
    approved_at timestamp with time zone,
    status_reason text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT production_plans_pkey PRIMARY KEY (id),
    CONSTRAINT uq_production_plans_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_production_plans_number UNIQUE (tenant_id, plan_number),
    CONSTRAINT ck_production_plans_status CHECK (status IN ('DRAFT', 'APPROVED', 'CLOSED', 'CANCELLED')),
    CONSTRAINT ck_production_plans_period CHECK (period_end >= period_start),
    CONSTRAINT ck_production_plans_approved
        CHECK (status IN ('DRAFT', 'CANCELLED') OR (approved_by IS NOT NULL AND approved_at IS NOT NULL)),
    CONSTRAINT production_plans_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_production_plans_facility FOREIGN KEY (tenant_id, facility_id) REFERENCES facilities(tenant_id, id)
);

CREATE INDEX ix_production_plans_facility ON production_plans (tenant_id, facility_id, period_start);

CREATE TABLE production_plan_lines (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    plan_id uuid NOT NULL,
    product_id uuid NOT NULL,
    quantity numeric(18,6) NOT NULL,
    due_date date NOT NULL,
    notes text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT production_plan_lines_pkey PRIMARY KEY (id),
    CONSTRAINT uq_production_plan_lines_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_production_plan_lines_quantity CHECK (quantity > 0),
    CONSTRAINT production_plan_lines_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_production_plan_lines_plan FOREIGN KEY (tenant_id, plan_id) REFERENCES production_plans(tenant_id, id),
    CONSTRAINT fk_production_plan_lines_product FOREIGN KEY (tenant_id, product_id) REFERENCES products(tenant_id, id)
);

CREATE INDEX ix_production_plan_lines_plan ON production_plan_lines (tenant_id, plan_id);

CREATE TABLE work_orders (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    work_order_number text NOT NULL,
    product_id uuid NOT NULL,
    facility_id uuid NOT NULL,
    production_line_id uuid,
    -- In the product's own unit.
    quantity numeric(18,6) NOT NULL,
    uom_id uuid NOT NULL,
    planned_start date NOT NULL,
    planned_end date NOT NULL,
    priority text DEFAULT 'NORMAL' NOT NULL,
    plan_line_id uuid,
    bom_id uuid,
    routing_id uuid,
    status text DEFAULT 'DRAFT' NOT NULL,
    -- The status an order on hold returns to.
    held_from text,
    status_reason text,
    notes text,
    submitted_by uuid,
    submitted_at timestamp with time zone,
    approved_by uuid,
    approved_at timestamp with time zone,
    released_by uuid,
    released_at timestamp with time zone,
    started_at timestamp with time zone,
    production_batch_id uuid,
    -- What the BOM or recipe and the routing said when the order was released.
    definition_snapshot jsonb,
    routing_snapshot jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT work_orders_pkey PRIMARY KEY (id),
    CONSTRAINT uq_work_orders_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_work_orders_number UNIQUE (tenant_id, work_order_number),
    CONSTRAINT uq_work_orders_batch UNIQUE (tenant_id, production_batch_id),
    CONSTRAINT ck_work_orders_quantity CHECK (quantity > 0),
    CONSTRAINT ck_work_orders_dates CHECK (planned_end >= planned_start),
    CONSTRAINT ck_work_orders_priority CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    CONSTRAINT ck_work_orders_status CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'RELEASED', 'IN_PROGRESS',
        'ON_HOLD', 'PARTIALLY_COMPLETED', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_work_orders_held CHECK ((status = 'ON_HOLD') = (held_from IS NOT NULL)),
    CONSTRAINT ck_work_orders_released CHECK (released_at IS NULL OR status NOT IN ('DRAFT', 'SUBMITTED', 'APPROVED')),
    CONSTRAINT ck_work_orders_started CHECK ((started_at IS NULL) = (production_batch_id IS NULL)),
    CONSTRAINT work_orders_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_work_orders_product FOREIGN KEY (tenant_id, product_id) REFERENCES products(tenant_id, id),
    CONSTRAINT fk_work_orders_facility FOREIGN KEY (tenant_id, facility_id) REFERENCES facilities(tenant_id, id),
    CONSTRAINT fk_work_orders_line FOREIGN KEY (tenant_id, production_line_id) REFERENCES production_lines(tenant_id, id),
    CONSTRAINT fk_work_orders_uom FOREIGN KEY (uom_id) REFERENCES units_of_measure(id),
    CONSTRAINT fk_work_orders_plan_line
        FOREIGN KEY (tenant_id, plan_line_id) REFERENCES production_plan_lines(tenant_id, id),
    CONSTRAINT fk_work_orders_bom FOREIGN KEY (tenant_id, bom_id) REFERENCES boms(tenant_id, id),
    CONSTRAINT fk_work_orders_routing FOREIGN KEY (tenant_id, routing_id) REFERENCES routings(tenant_id, id),
    CONSTRAINT fk_work_orders_batch FOREIGN KEY (tenant_id, production_batch_id)
        REFERENCES production_batches(tenant_id, id)
);

CREATE INDEX ix_work_orders_status ON work_orders (tenant_id, status, planned_start);
CREATE INDEX ix_work_orders_facility ON work_orders (tenant_id, facility_id);
CREATE INDEX ix_work_orders_product ON work_orders (tenant_id, product_id);
CREATE INDEX ix_work_orders_plan_line ON work_orders (tenant_id, plan_line_id) WHERE plan_line_id IS NOT NULL;

-- What the order needs, worked out on release from its BOM or recipe, one level deep, in each component's own unit.
CREATE TABLE work_order_materials (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    work_order_id uuid NOT NULL,
    line_number integer NOT NULL,
    material_id uuid,
    component_product_id uuid,
    required_quantity numeric(18,6) NOT NULL,
    uom_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT work_order_materials_pkey PRIMARY KEY (id),
    CONSTRAINT uq_work_order_materials_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_work_order_materials_line UNIQUE (tenant_id, work_order_id, line_number),
    CONSTRAINT ck_work_order_materials_component CHECK ((material_id IS NULL) <> (component_product_id IS NULL)),
    CONSTRAINT ck_work_order_materials_quantity CHECK (required_quantity > 0),
    CONSTRAINT work_order_materials_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_work_order_materials_order FOREIGN KEY (tenant_id, work_order_id) REFERENCES work_orders(tenant_id, id),
    CONSTRAINT fk_work_order_materials_material FOREIGN KEY (tenant_id, material_id) REFERENCES materials(tenant_id, id),
    CONSTRAINT fk_work_order_materials_product
        FOREIGN KEY (tenant_id, component_product_id) REFERENCES products(tenant_id, id),
    CONSTRAINT fk_work_order_materials_uom FOREIGN KEY (uom_id) REFERENCES units_of_measure(id)
);

-- The routing's operations as released. Shop-floor execution (phase 4) moves them through their statuses.
CREATE TABLE work_order_operations (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    work_order_id uuid NOT NULL,
    sequence integer NOT NULL,
    name text NOT NULL,
    work_center_id uuid NOT NULL,
    planned_setup_minutes numeric(10,2) NOT NULL,
    planned_run_minutes numeric(14,4) NOT NULL,
    instructions text,
    status text DEFAULT 'PENDING' NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT work_order_operations_pkey PRIMARY KEY (id),
    CONSTRAINT uq_work_order_operations_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_work_order_operations_sequence UNIQUE (tenant_id, work_order_id, sequence),
    CONSTRAINT ck_work_order_operations_status
        CHECK (status IN ('PENDING', 'READY', 'IN_PROGRESS', 'PAUSED', 'COMPLETED', 'BLOCKED', 'CANCELLED')),
    CONSTRAINT ck_work_order_operations_times CHECK (planned_setup_minutes >= 0 AND planned_run_minutes >= 0),
    CONSTRAINT work_order_operations_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_work_order_operations_order
        FOREIGN KEY (tenant_id, work_order_id) REFERENCES work_orders(tenant_id, id),
    CONSTRAINT fk_work_order_operations_work_center
        FOREIGN KEY (tenant_id, work_center_id) REFERENCES work_centers(tenant_id, id)
);

-- Every status a work order took, with who and why. Never changed or removed.
CREATE TABLE work_order_status_history (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    work_order_id uuid NOT NULL,
    from_status text,
    to_status text NOT NULL,
    reason text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT work_order_status_history_pkey PRIMARY KEY (id),
    CONSTRAINT work_order_status_history_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_work_order_status_history_order
        FOREIGN KEY (tenant_id, work_order_id) REFERENCES work_orders(tenant_id, id)
);

CREATE INDEX ix_work_order_status_history_order ON work_order_status_history (tenant_id, work_order_id, created_at);

SELECT apply_tenant_standards('production_plans');
SELECT apply_tenant_standards('production_plan_lines');
SELECT apply_tenant_standards('work_orders');
SELECT apply_tenant_standards('work_order_materials');
SELECT apply_tenant_standards('work_order_operations');
SELECT apply_tenant_standards('work_order_status_history');

CREATE TRIGGER trg_append_only BEFORE UPDATE OR DELETE ON work_order_status_history
    FOR EACH ROW EXECUTE FUNCTION refuse_history_change();

-- A work order is never deleted, and its released requirements are never rewritten. Phase 3 and 4 add their own
-- columns for reservation and execution; the released quantity and component stay as they were.
CREATE FUNCTION protect_work_order() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION '% % is production history and cannot be deleted', TG_TABLE_NAME, OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.status IN ('COMPLETED', 'CANCELLED') THEN
        RAISE EXCEPTION 'work order % is % and cannot be changed', OLD.work_order_number, OLD.status
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.released_at IS NOT NULL AND (
        NEW.product_id IS DISTINCT FROM OLD.product_id
        OR NEW.quantity IS DISTINCT FROM OLD.quantity
        OR NEW.bom_id IS DISTINCT FROM OLD.bom_id
        OR NEW.routing_id IS DISTINCT FROM OLD.routing_id
        OR NEW.definition_snapshot IS DISTINCT FROM OLD.definition_snapshot
        OR NEW.routing_snapshot IS DISTINCT FROM OLD.routing_snapshot
        OR NEW.production_batch_id IS DISTINCT FROM COALESCE(OLD.production_batch_id, NEW.production_batch_id)) THEN
        RAISE EXCEPTION 'work order % was released, so what it makes and from what cannot change', OLD.work_order_number
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_protect_work_order BEFORE UPDATE OR DELETE ON work_orders
    FOR EACH ROW EXECUTE FUNCTION protect_work_order();

CREATE FUNCTION protect_released_requirement() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION '% % cannot be deleted', TG_TABLE_NAME, OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.work_order_id IS DISTINCT FROM OLD.work_order_id
        OR (TG_TABLE_NAME = 'work_order_materials' AND (
            NEW.required_quantity IS DISTINCT FROM OLD.required_quantity
            OR NEW.material_id IS DISTINCT FROM OLD.material_id
            OR NEW.component_product_id IS DISTINCT FROM OLD.component_product_id
            OR NEW.uom_id IS DISTINCT FROM OLD.uom_id)) THEN
        RAISE EXCEPTION '% % was released and cannot be rewritten', TG_TABLE_NAME, OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_protect_requirement BEFORE UPDATE OR DELETE ON work_order_materials
    FOR EACH ROW EXECUTE FUNCTION protect_released_requirement();
CREATE TRIGGER trg_protect_requirement BEFORE UPDATE OR DELETE ON work_order_operations
    FOR EACH ROW EXECUTE FUNCTION protect_released_requirement();

-- Permissions, and a Production Planner role.
INSERT INTO permissions (code, module, description) VALUES
    ('WORK_ORDER_VIEW', 'Manufacturing', 'View work orders'),
    ('WORK_ORDER_MANAGE', 'Manufacturing', 'Draft, change and submit work orders, and cancel them before approval'),
    ('WORK_ORDER_APPROVE', 'Manufacturing', 'Approve work orders, send them back, and cancel approved ones'),
    ('WORK_ORDER_RELEASE', 'Manufacturing', 'Release, start, hold and resume work orders'),
    ('PRODUCTION_PLAN_MANAGE', 'Manufacturing', 'Draft production plans and raise work orders from them'),
    ('PRODUCTION_PLAN_APPROVE', 'Manufacturing', 'Approve, close and cancel production plans');

ALTER TABLE roles NO FORCE ROW LEVEL SECURITY;
ALTER TABLE role_permissions NO FORCE ROW LEVEL SECURITY;

INSERT INTO roles (id, tenant_id, code, name, description) VALUES
    ('3e9b1c52-8f0a-4d6e-b7a1-5c2d9e8f4a17', NULL, 'PRODUCTION_PLANNER', 'Production Planner',
     'Plans production and raises work orders');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r CROSS JOIN permissions p
WHERE r.tenant_id IS NULL
  AND (
    (r.code IN ('ORGANISATION_OWNER', 'ORGANISATION_ADMIN', 'PLANT_MANAGER', 'PRODUCTION_MANAGER')
        AND p.code IN ('WORK_ORDER_VIEW', 'WORK_ORDER_MANAGE', 'WORK_ORDER_APPROVE', 'WORK_ORDER_RELEASE',
                       'PRODUCTION_PLAN_MANAGE', 'PRODUCTION_PLAN_APPROVE'))
    OR (r.code IN ('QUALITY_MANAGER', 'QUALITY_ENGINEER', 'QUALITY_INSPECTOR', 'AUDITOR', 'VIEWER')
        AND p.code = 'WORK_ORDER_VIEW')
    OR (r.code = 'PRODUCTION_PLANNER' AND p.code IN (
        'MANUFACTURING_VIEW', 'WORK_ORDER_VIEW', 'WORK_ORDER_MANAGE', 'PRODUCTION_PLAN_MANAGE',
        'FACILITY_VIEW', 'PRODUCT_VIEW', 'PURCHASE_VIEW', 'TRACEABILITY_VIEW', 'REPORT_VIEW'))
  );

ALTER TABLE roles FORCE ROW LEVEL SECURITY;
ALTER TABLE role_permissions FORCE ROW LEVEL SECURITY;

UPDATE feature_definitions
SET description = 'BOMs, recipes, routings, work centres, machines, production plans and work orders'
WHERE code = 'MANUFACTURING';
