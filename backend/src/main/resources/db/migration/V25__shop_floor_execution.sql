-- Manufacturing phase 4: shop-floor execution of work order operations.
-- See docs/MANUFACTURING.md.

-- Rules for running operations. Each organisation chooses.
ALTER TABLE manufacturing_settings
    -- An operation becomes ready only once the one before it is complete.
    ADD COLUMN operations_in_sequence boolean DEFAULT true NOT NULL,
    -- How far past the order quantity the first operation may report, in percent.
    ADD COLUMN over_report_percent numeric(5,2) DEFAULT 0 NOT NULL,
    -- Starting an operation at a work centre with machines needs one of them.
    ADD COLUMN operation_requires_machine boolean DEFAULT false NOT NULL,
    ADD CONSTRAINT ck_manufacturing_settings_over_report CHECK (over_report_percent >= 0 AND over_report_percent <= 100);

-- What happens on the floor. Quantities are in the work order's unit and only ever change through reports.
ALTER TABLE work_order_operations
    ADD COLUMN operator_id uuid,
    ADD COLUMN machine_id uuid,
    ADD COLUMN status_reason text,
    -- Where a blocked operation goes back to.
    ADD COLUMN blocked_from text,
    ADD COLUMN started_at timestamp with time zone,
    ADD COLUMN completed_at timestamp with time zone,
    -- When it last began running; null while not running. Running time is added up when it stops.
    ADD COLUMN running_since timestamp with time zone,
    ADD COLUMN running_seconds bigint DEFAULT 0 NOT NULL,
    ADD COLUMN actual_setup_minutes numeric(10,2),
    ADD COLUMN good_quantity numeric(18,6) DEFAULT 0 NOT NULL,
    ADD COLUMN rejected_quantity numeric(18,6) DEFAULT 0 NOT NULL,
    ADD COLUMN scrap_quantity numeric(18,6) DEFAULT 0 NOT NULL,
    ADD COLUMN rework_quantity numeric(18,6) DEFAULT 0 NOT NULL,
    -- The routing operation's parameters as released, with the recipe's, for measurements.
    ADD COLUMN parameters jsonb DEFAULT '[]'::jsonb NOT NULL,
    ADD CONSTRAINT fk_work_order_operations_machine FOREIGN KEY (tenant_id, machine_id) REFERENCES machines(tenant_id, id),
    ADD CONSTRAINT fk_work_order_operations_operator FOREIGN KEY (operator_id) REFERENCES users(id),
    ADD CONSTRAINT ck_work_order_operations_quantities CHECK (
        good_quantity >= 0 AND rejected_quantity >= 0 AND scrap_quantity >= 0
        AND rework_quantity >= 0 AND rework_quantity <= rejected_quantity),
    ADD CONSTRAINT ck_work_order_operations_blocked
        CHECK ((status = 'BLOCKED') = (blocked_from IS NOT NULL)),
    ADD CONSTRAINT ck_work_order_operations_running
        CHECK ((status = 'IN_PROGRESS') = (running_since IS NOT NULL)),
    ADD CONSTRAINT ck_work_order_operations_setup CHECK (actual_setup_minutes IS NULL OR actual_setup_minutes >= 0);

-- A machine runs one operation at a time.
CREATE UNIQUE INDEX uq_work_order_operations_running_machine
    ON work_order_operations (tenant_id, machine_id) WHERE status = 'IN_PROGRESS' AND machine_id IS NOT NULL;
CREATE INDEX ix_work_order_operations_status ON work_order_operations (tenant_id, status);
CREATE INDEX ix_work_order_operations_operator ON work_order_operations (tenant_id, operator_id)
    WHERE operator_id IS NOT NULL;

-- What a released operation is and where it stands may change only through execution; a closed one not at all.
CREATE OR REPLACE FUNCTION protect_released_requirement() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION '% % cannot be deleted', TG_TABLE_NAME, OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.work_order_id IS DISTINCT FROM OLD.work_order_id THEN
        RAISE EXCEPTION '% % was released and cannot be rewritten', TG_TABLE_NAME, OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    -- Each table's columns are only read inside its own branch: a record has no fields of another table.
    IF TG_TABLE_NAME = 'work_order_materials' THEN
        IF NEW.required_quantity IS DISTINCT FROM OLD.required_quantity
            OR NEW.material_id IS DISTINCT FROM OLD.material_id
            OR NEW.component_product_id IS DISTINCT FROM OLD.component_product_id
            OR NEW.uom_id IS DISTINCT FROM OLD.uom_id THEN
            RAISE EXCEPTION '% % was released and cannot be rewritten', TG_TABLE_NAME, OLD.id
                USING ERRCODE = 'restrict_violation';
        END IF;
    ELSIF TG_TABLE_NAME = 'work_order_operations' THEN
        IF NEW.sequence IS DISTINCT FROM OLD.sequence
            OR NEW.work_center_id IS DISTINCT FROM OLD.work_center_id
            OR NEW.planned_setup_minutes IS DISTINCT FROM OLD.planned_setup_minutes
            OR NEW.planned_run_minutes IS DISTINCT FROM OLD.planned_run_minutes
            OR NEW.parameters IS DISTINCT FROM OLD.parameters THEN
            RAISE EXCEPTION '% % was released and cannot be rewritten', TG_TABLE_NAME, OLD.id
                USING ERRCODE = 'restrict_violation';
        END IF;
        IF OLD.status IN ('COMPLETED', 'CANCELLED') THEN
            RAISE EXCEPTION 'operation % is % and cannot be changed', OLD.id, lower(OLD.status)
                USING ERRCODE = 'restrict_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- Each quantity report on an operation, and each reversal of one. Never changed or removed.
CREATE TABLE operation_reports (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    work_order_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    -- Negative on a reversal, which points at the report it cancels.
    good_quantity numeric(18,6) NOT NULL,
    rejected_quantity numeric(18,6) NOT NULL,
    scrap_quantity numeric(18,6) NOT NULL,
    rework_quantity numeric(18,6) NOT NULL,
    reverses_report_id uuid,
    comment text,
    request_key text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT operation_reports_pkey PRIMARY KEY (id),
    CONSTRAINT uq_operation_reports_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_operation_reports_key UNIQUE (tenant_id, operation_id, request_key),
    CONSTRAINT uq_operation_reports_reversal UNIQUE (tenant_id, reverses_report_id),
    CONSTRAINT ck_operation_reports_sign CHECK (
        (reverses_report_id IS NULL AND good_quantity >= 0 AND rejected_quantity >= 0 AND scrap_quantity >= 0
            AND rework_quantity >= 0 AND rework_quantity <= rejected_quantity
            AND good_quantity + rejected_quantity + scrap_quantity > 0)
        OR (reverses_report_id IS NOT NULL AND good_quantity <= 0 AND rejected_quantity <= 0 AND scrap_quantity <= 0
            AND rework_quantity <= 0)),
    CONSTRAINT operation_reports_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_operation_reports_order FOREIGN KEY (tenant_id, work_order_id) REFERENCES work_orders(tenant_id, id),
    CONSTRAINT fk_operation_reports_operation
        FOREIGN KEY (tenant_id, operation_id) REFERENCES work_order_operations(tenant_id, id),
    CONSTRAINT fk_operation_reports_reverses
        FOREIGN KEY (tenant_id, reverses_report_id) REFERENCES operation_reports(tenant_id, id)
);

CREATE INDEX ix_operation_reports_operation ON operation_reports (tenant_id, operation_id, created_at);

-- A measured value of a process parameter, with the limits it was held to. Never changed or removed.
CREATE TABLE operation_measurements (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    work_order_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    parameter_code text NOT NULL,
    parameter_name text NOT NULL,
    unit text,
    target_value numeric(18,6),
    min_value numeric(18,6),
    max_value numeric(18,6),
    measured_value numeric(18,6) NOT NULL,
    within_limits boolean NOT NULL,
    comment text,
    request_key text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT operation_measurements_pkey PRIMARY KEY (id),
    CONSTRAINT uq_operation_measurements_key UNIQUE (tenant_id, operation_id, request_key),
    CONSTRAINT operation_measurements_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_operation_measurements_order
        FOREIGN KEY (tenant_id, work_order_id) REFERENCES work_orders(tenant_id, id),
    CONSTRAINT fk_operation_measurements_operation
        FOREIGN KEY (tenant_id, operation_id) REFERENCES work_order_operations(tenant_id, id)
);

CREATE INDEX ix_operation_measurements_operation ON operation_measurements (tenant_id, operation_id, created_at);

-- An operation's timeline: status changes, assignments and comments. Never changed or removed.
CREATE TABLE operation_events (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    work_order_id uuid NOT NULL,
    operation_id uuid NOT NULL,
    event_type text NOT NULL,
    from_status text,
    to_status text,
    -- For a pause or a block: why the operation stopped.
    loss_category text,
    operator_id uuid,
    machine_id uuid,
    comment text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT operation_events_pkey PRIMARY KEY (id),
    CONSTRAINT ck_operation_events_type CHECK (event_type IN (
        'READY', 'STARTED', 'PAUSED', 'RESUMED', 'COMPLETED', 'BLOCKED', 'UNBLOCKED', 'CANCELLED', 'ASSIGNED',
        'COMMENT')),
    CONSTRAINT ck_operation_events_loss CHECK (loss_category IS NULL OR loss_category IN (
        'MACHINE_BREAKDOWN', 'MATERIAL_SHORTAGE', 'POWER_FAILURE', 'QUALITY_ISSUE', 'OPERATOR_UNAVAILABLE',
        'PLANNED_MAINTENANCE', 'CHANGEOVER', 'ORDER_ON_HOLD', 'OTHER')),
    CONSTRAINT operation_events_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_operation_events_order FOREIGN KEY (tenant_id, work_order_id) REFERENCES work_orders(tenant_id, id),
    CONSTRAINT fk_operation_events_operation
        FOREIGN KEY (tenant_id, operation_id) REFERENCES work_order_operations(tenant_id, id),
    CONSTRAINT fk_operation_events_machine FOREIGN KEY (tenant_id, machine_id) REFERENCES machines(tenant_id, id)
);

CREATE INDEX ix_operation_events_operation ON operation_events (tenant_id, operation_id, created_at);

SELECT apply_tenant_standards('operation_reports');
SELECT apply_tenant_standards('operation_measurements');
SELECT apply_tenant_standards('operation_events');

CREATE TRIGGER trg_append_only BEFORE UPDATE OR DELETE ON operation_reports
    FOR EACH ROW EXECUTE FUNCTION refuse_history_change();
CREATE TRIGGER trg_append_only BEFORE UPDATE OR DELETE ON operation_measurements
    FOR EACH ROW EXECUTE FUNCTION refuse_history_change();
CREATE TRIGGER trg_append_only BEFORE UPDATE OR DELETE ON operation_events
    FOR EACH ROW EXECUTE FUNCTION refuse_history_change();

-- Permissions, and a Production Operator role.
INSERT INTO permissions (code, module, description) VALUES
    ('PRODUCTION_EXECUTE', 'Manufacturing',
     'Run work order operations: start, pause, complete, report quantities, record measurements, comments and photos'),
    ('PRODUCTION_SUPERVISE', 'Manufacturing',
     'Assign operators and machines, block, unblock and skip operations, reverse reports, and run anyone''s operation');

ALTER TABLE roles NO FORCE ROW LEVEL SECURITY;
ALTER TABLE role_permissions NO FORCE ROW LEVEL SECURITY;

INSERT INTO roles (id, tenant_id, code, name, description) VALUES
    ('a6d3f0e1-52c8-4b97-8e14-7f2b9c6d0e35', NULL, 'PRODUCTION_OPERATOR', 'Production Operator',
     'Runs operations on the shop floor');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r CROSS JOIN permissions p
WHERE r.tenant_id IS NULL
  AND (
    (r.code IN ('ORGANISATION_OWNER', 'ORGANISATION_ADMIN', 'PLANT_MANAGER', 'PRODUCTION_MANAGER')
        AND p.code IN ('PRODUCTION_EXECUTE', 'PRODUCTION_SUPERVISE'))
    OR (r.code = 'PRODUCTION_OPERATOR' AND p.code IN (
        'WORK_ORDER_VIEW', 'PRODUCTION_EXECUTE', 'WORK_ORDER_MATERIAL_ISSUE', 'FACILITY_VIEW', 'PRODUCT_VIEW',
        'TRACEABILITY_VIEW'))
  );

ALTER TABLE roles FORCE ROW LEVEL SECURITY;
ALTER TABLE role_permissions FORCE ROW LEVEL SECURITY;

UPDATE feature_definitions
SET description = 'BOMs, recipes, routings, work centres, machines, production plans, work orders and shop-floor execution'
WHERE code = 'MANUFACTURING';
