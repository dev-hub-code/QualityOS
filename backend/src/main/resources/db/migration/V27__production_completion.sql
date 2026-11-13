-- Manufacturing phase 6: finished-goods receipt and closing work orders.
-- Finished goods are the order's existing production batch, completed through traceability: no separate stock is
-- kept here. See docs/MANUFACTURING.md.

ALTER TABLE manufacturing_settings
    -- A receipt this far below the order quantity, in percent, still closes the order as completed.
    ADD COLUMN completion_tolerance_percent numeric(5,2) DEFAULT 0 NOT NULL,
    -- A material line whose issue differs from what the received quantity needed by more than this, in percent,
    -- needs a reason at receipt.
    ADD COLUMN material_variance_percent numeric(5,2) DEFAULT 5 NOT NULL,
    -- Closing waits until the batch's final inspection has decided: released or rejected.
    ADD COLUMN completion_requires_final_inspection boolean DEFAULT true NOT NULL,
    -- Discrete products are received with one serial number for each unit.
    ADD COLUMN serial_tracking boolean DEFAULT false NOT NULL,
    ADD CONSTRAINT ck_manufacturing_settings_completion_tolerance
        CHECK (completion_tolerance_percent >= 0 AND completion_tolerance_percent <= 100),
    ADD CONSTRAINT ck_manufacturing_settings_material_variance
        CHECK (material_variance_percent >= 0 AND material_variance_percent <= 100);

ALTER TABLE work_orders
    ADD COLUMN completed_by uuid,
    ADD COLUMN completed_at timestamp with time zone,
    ADD CONSTRAINT ck_work_orders_completed
        CHECK ((status IN ('COMPLETED', 'PARTIALLY_COMPLETED')) = (completed_at IS NOT NULL));

-- A closed order, completed in full or short, never changes again.
CREATE OR REPLACE FUNCTION protect_work_order() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION '% % is production history and cannot be deleted', TG_TABLE_NAME, OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.status IN ('COMPLETED', 'PARTIALLY_COMPLETED', 'CANCELLED') THEN
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

-- The finished goods a work order put into its batch: one receipt per order, never changed.
CREATE TABLE finished_goods_receipts (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    work_order_id uuid NOT NULL,
    production_batch_id uuid NOT NULL,
    -- In the work order's unit.
    quantity numeric(18,6) NOT NULL,
    -- What the order was for, what the operations turned out good, and what was rejected and scrapped on them.
    ordered_quantity numeric(18,6) NOT NULL,
    expected_quantity numeric(18,6),
    rejected_quantity numeric(18,6) NOT NULL,
    scrap_quantity numeric(18,6) NOT NULL,
    reworked_quantity numeric(18,6) NOT NULL,
    -- Why less was received than ordered, and why material use was off, when it was.
    short_reason text,
    variance_reason text,
    -- Each material line: what the received quantity needed, what was issued, and the difference.
    material_variances jsonb DEFAULT '[]'::jsonb NOT NULL,
    serial_count integer DEFAULT 0 NOT NULL,
    request_key text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT finished_goods_receipts_pkey PRIMARY KEY (id),
    CONSTRAINT uq_finished_goods_receipts_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_finished_goods_receipts_order UNIQUE (tenant_id, work_order_id),
    CONSTRAINT uq_finished_goods_receipts_batch UNIQUE (tenant_id, production_batch_id),
    CONSTRAINT ck_finished_goods_receipts_quantity CHECK (quantity > 0),
    CONSTRAINT ck_finished_goods_receipts_counts CHECK (rejected_quantity >= 0 AND scrap_quantity >= 0
        AND reworked_quantity >= 0 AND serial_count >= 0),
    CONSTRAINT finished_goods_receipts_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_finished_goods_receipts_order
        FOREIGN KEY (tenant_id, work_order_id) REFERENCES work_orders(tenant_id, id),
    CONSTRAINT fk_finished_goods_receipts_batch
        FOREIGN KEY (tenant_id, production_batch_id) REFERENCES production_batches(tenant_id, id)
);

SELECT apply_tenant_standards('finished_goods_receipts');

CREATE TRIGGER trg_append_only BEFORE UPDATE OR DELETE ON finished_goods_receipts
    FOR EACH ROW EXECUTE FUNCTION refuse_history_change();

INSERT INTO permissions (code, module, description) VALUES
    ('WORK_ORDER_COMPLETE', 'Manufacturing', 'Receive finished goods and close work orders');

ALTER TABLE roles NO FORCE ROW LEVEL SECURITY;
ALTER TABLE role_permissions NO FORCE ROW LEVEL SECURITY;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r CROSS JOIN permissions p
WHERE r.tenant_id IS NULL
  AND r.code IN ('ORGANISATION_OWNER', 'ORGANISATION_ADMIN', 'PLANT_MANAGER', 'PRODUCTION_MANAGER')
  AND p.code = 'WORK_ORDER_COMPLETE';

ALTER TABLE roles FORCE ROW LEVEL SECURITY;
ALTER TABLE role_permissions FORCE ROW LEVEL SECURITY;

UPDATE feature_definitions
SET description = 'BOMs, recipes, routings, work centres, machines, production plans, work orders, shop-floor execution, quality checkpoints, rework and finished-goods receipt'
WHERE code = 'MANUFACTURING';
