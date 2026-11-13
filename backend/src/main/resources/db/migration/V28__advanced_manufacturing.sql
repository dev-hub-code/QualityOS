-- Manufacturing phase 7: downtime, shift days and breaks for capacity, cost rates, and the permissions for costing and
-- production analytics. See docs/MANUFACTURING.md.

-- A facility's shifts (from V3) gain the days they run and a break, so capacity can be worked out from them.
-- Existing shifts are taken to run every day, as they had no days before.
ALTER TABLE shifts
    ADD COLUMN break_minutes integer DEFAULT 0 NOT NULL,
    -- Monday 1, Tuesday 2, Wednesday 4 ... Sunday 64.
    ADD COLUMN days_of_week integer DEFAULT 127 NOT NULL,
    ADD CONSTRAINT ck_shifts_days CHECK (days_of_week BETWEEN 1 AND 127),
    ADD CONSTRAINT ck_shifts_break CHECK (break_minutes >= 0 AND break_minutes < 1440);

ALTER TABLE work_centers
    -- How many operations the centre runs side by side when it has no machines; with machines, each active one counts.
    ADD COLUMN capacity_units integer DEFAULT 1 NOT NULL,
    -- Per hour of running time, in the organisation's currency. Blank means not costed.
    ADD COLUMN labour_rate numeric(18,4),
    ADD COLUMN machine_rate numeric(18,4),
    ADD COLUMN overhead_rate numeric(18,4),
    ADD CONSTRAINT ck_work_centers_capacity CHECK (capacity_units BETWEEN 1 AND 1000),
    ADD CONSTRAINT ck_work_centers_rates CHECK (COALESCE(labour_rate, 0) >= 0 AND COALESCE(machine_rate, 0) >= 0
        AND COALESCE(overhead_rate, 0) >= 0);

-- Every stop of production: from the shop floor (an operation paused or blocked), from a machine set to breakdown or
-- maintenance, or entered by hand, including maintenance planned ahead. Never deleted.
CREATE TABLE downtime_records (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    facility_id uuid NOT NULL,
    work_center_id uuid NOT NULL,
    machine_id uuid,
    work_order_id uuid,
    operation_id uuid,
    source text NOT NULL,
    category text NOT NULL,
    -- Planned maintenance: taken out of planned production time, not counted against availability.
    planned boolean NOT NULL,
    started_at timestamp with time zone NOT NULL,
    ended_at timestamp with time zone,
    ended_by uuid,
    reason text NOT NULL,
    responsible_department text,
    comments text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT downtime_records_pkey PRIMARY KEY (id),
    CONSTRAINT uq_downtime_records_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_downtime_records_source CHECK (source IN ('SHOP_FLOOR', 'MACHINE', 'MANUAL')),
    CONSTRAINT ck_downtime_records_category CHECK (category IN ('MACHINE_BREAKDOWN', 'MATERIAL_SHORTAGE',
        'POWER_FAILURE', 'QUALITY_ISSUE', 'OPERATOR_UNAVAILABLE', 'PLANNED_MAINTENANCE', 'CHANGEOVER',
        'ORDER_ON_HOLD', 'OTHER')),
    CONSTRAINT ck_downtime_records_period CHECK (ended_at IS NULL OR ended_at >= started_at),
    CONSTRAINT ck_downtime_records_reason CHECK (length(btrim(reason)) > 0),
    CONSTRAINT ck_downtime_records_operation CHECK ((source = 'SHOP_FLOOR') = (operation_id IS NOT NULL)),
    CONSTRAINT downtime_records_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_downtime_records_facility FOREIGN KEY (tenant_id, facility_id) REFERENCES facilities(tenant_id, id),
    CONSTRAINT fk_downtime_records_centre FOREIGN KEY (tenant_id, work_center_id) REFERENCES work_centers(tenant_id, id),
    CONSTRAINT fk_downtime_records_machine FOREIGN KEY (tenant_id, machine_id) REFERENCES machines(tenant_id, id),
    CONSTRAINT fk_downtime_records_order FOREIGN KEY (tenant_id, work_order_id) REFERENCES work_orders(tenant_id, id),
    CONSTRAINT fk_downtime_records_operation
        FOREIGN KEY (tenant_id, operation_id) REFERENCES work_order_operations(tenant_id, id)
);

-- An operation, or a machine set down by hand, has at most one stop open at a time.
CREATE UNIQUE INDEX uq_downtime_records_open_operation ON downtime_records (tenant_id, operation_id)
    WHERE ended_at IS NULL AND operation_id IS NOT NULL;
CREATE UNIQUE INDEX uq_downtime_records_open_machine ON downtime_records (tenant_id, machine_id)
    WHERE ended_at IS NULL AND source = 'MACHINE';
CREATE INDEX ix_downtime_records_period ON downtime_records (tenant_id, facility_id, started_at);

SELECT apply_tenant_standards('downtime_records');

-- Stops are history: never deleted, and a stop from the floor or a machine keeps its times once ended.
CREATE OR REPLACE FUNCTION protect_downtime_record() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'downtime record % is production history and cannot be deleted', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.source IS DISTINCT FROM OLD.source OR NEW.facility_id IS DISTINCT FROM OLD.facility_id
        OR NEW.operation_id IS DISTINCT FROM OLD.operation_id OR NEW.work_order_id IS DISTINCT FROM OLD.work_order_id
        OR NEW.machine_id IS DISTINCT FROM OLD.machine_id OR NEW.work_center_id IS DISTINCT FROM OLD.work_center_id THEN
        RAISE EXCEPTION 'what downtime record % stopped cannot change', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.source <> 'MANUAL' AND OLD.ended_at IS NOT NULL AND (NEW.started_at IS DISTINCT FROM OLD.started_at
        OR NEW.ended_at IS DISTINCT FROM OLD.ended_at OR NEW.category IS DISTINCT FROM OLD.category) THEN
        RAISE EXCEPTION 'downtime record % was recorded as it happened and cannot be retimed', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_protect_downtime_record BEFORE UPDATE OR DELETE ON downtime_records
    FOR EACH ROW EXECUTE FUNCTION protect_downtime_record();

INSERT INTO permissions (code, module, description) VALUES
    ('DOWNTIME_MANAGE', 'Manufacturing', 'Record, end and correct production downtime'),
    ('PRODUCTION_COST_VIEW', 'Manufacturing', 'See planned and actual production costs'),
    ('PRODUCTION_ANALYTICS_VIEW', 'Manufacturing', 'See production analytics and OEE');

ALTER TABLE roles NO FORCE ROW LEVEL SECURITY;
ALTER TABLE role_permissions NO FORCE ROW LEVEL SECURITY;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r CROSS JOIN permissions p
WHERE r.tenant_id IS NULL
  AND (
    (r.code IN ('ORGANISATION_OWNER', 'ORGANISATION_ADMIN', 'PLANT_MANAGER', 'PRODUCTION_MANAGER')
        AND p.code IN ('DOWNTIME_MANAGE', 'PRODUCTION_COST_VIEW', 'PRODUCTION_ANALYTICS_VIEW'))
    OR (r.code = 'PRODUCTION_PLANNER' AND p.code IN ('PRODUCTION_COST_VIEW', 'PRODUCTION_ANALYTICS_VIEW'))
    OR (r.code = 'QUALITY_MANAGER' AND p.code = 'PRODUCTION_ANALYTICS_VIEW')
  );

ALTER TABLE roles FORCE ROW LEVEL SECURITY;
ALTER TABLE role_permissions FORCE ROW LEVEL SECURITY;

UPDATE feature_definitions
SET description = 'BOMs, recipes, routings, work centres, machines, production plans, work orders, shop-floor execution, quality checkpoints, rework, finished-goods receipt, downtime, scheduling, costing and production analytics'
WHERE code = 'MANUFACTURING';
