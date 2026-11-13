-- Manufacturing phase 3: material availability, reservation, issue and return for work orders.
-- See docs/MANUFACTURING.md.

-- Stock set aside for work orders stays on the lot, beside what was used, so ordinary consumption cannot take it.
ALTER TABLE material_receipt_items
    ADD COLUMN reserved_quantity numeric(18,6) DEFAULT 0 NOT NULL,
    ADD CONSTRAINT ck_receipt_items_reserved
        CHECK (reserved_quantity >= 0 AND consumed_quantity + reserved_quantity <= quantity);

ALTER TABLE manufacturing_settings
    ADD COLUMN reserve_on_release boolean DEFAULT false NOT NULL,
    ADD COLUMN start_requires_materials boolean DEFAULT false NOT NULL,
    ADD COLUMN allow_over_issue boolean DEFAULT false NOT NULL;

-- Stock on one lot set aside for one material line of a work order, in the lot's unit.
CREATE TABLE material_reservations (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    work_order_id uuid NOT NULL,
    work_order_material_id uuid NOT NULL,
    lot_id uuid NOT NULL,
    quantity numeric(18,6) NOT NULL,
    -- What is still set aside: issuing or releasing it brings this down.
    open_quantity numeric(18,6) NOT NULL,
    uom_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT material_reservations_pkey PRIMARY KEY (id),
    CONSTRAINT uq_material_reservations_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_material_reservations_quantity CHECK (quantity > 0 AND open_quantity >= 0 AND open_quantity <= quantity),
    CONSTRAINT material_reservations_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_material_reservations_order FOREIGN KEY (tenant_id, work_order_id) REFERENCES work_orders(tenant_id, id),
    CONSTRAINT fk_material_reservations_requirement
        FOREIGN KEY (tenant_id, work_order_material_id) REFERENCES work_order_materials(tenant_id, id),
    CONSTRAINT fk_material_reservations_lot FOREIGN KEY (tenant_id, lot_id) REFERENCES material_receipt_items(tenant_id, id),
    CONSTRAINT fk_material_reservations_uom FOREIGN KEY (uom_id) REFERENCES units_of_measure(id)
);

CREATE INDEX ix_material_reservations_order ON material_reservations (tenant_id, work_order_id);
CREATE INDEX ix_material_reservations_open_lot ON material_reservations (tenant_id, lot_id) WHERE open_quantity > 0;

-- Every reservation, release of a reservation, issue and return made for a work order. Never changed or removed.
CREATE TABLE work_order_material_movements (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    work_order_id uuid NOT NULL,
    work_order_material_id uuid,
    movement_type text NOT NULL,
    lot_id uuid,
    source_batch_id uuid,
    -- In the source's unit.
    quantity numeric(18,6) NOT NULL,
    uom_id uuid NOT NULL,
    reservation_id uuid,
    -- The consumption entry an issue made, or a return closed.
    batch_relationship_id uuid,
    reason text,
    -- The client's Idempotency-Key: a request repeated with the same key is not applied twice.
    request_key text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT work_order_material_movements_pkey PRIMARY KEY (id),
    CONSTRAINT uq_work_order_material_movements_key UNIQUE (tenant_id, work_order_id, request_key),
    CONSTRAINT ck_work_order_material_movements_type
        CHECK (movement_type IN ('RESERVED', 'UNRESERVED', 'ISSUED', 'RETURNED')),
    CONSTRAINT ck_work_order_material_movements_source CHECK ((lot_id IS NULL) <> (source_batch_id IS NULL)),
    CONSTRAINT ck_work_order_material_movements_quantity CHECK (quantity > 0),
    CONSTRAINT work_order_material_movements_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_work_order_material_movements_order
        FOREIGN KEY (tenant_id, work_order_id) REFERENCES work_orders(tenant_id, id),
    CONSTRAINT fk_work_order_material_movements_requirement
        FOREIGN KEY (tenant_id, work_order_material_id) REFERENCES work_order_materials(tenant_id, id),
    CONSTRAINT fk_work_order_material_movements_lot
        FOREIGN KEY (tenant_id, lot_id) REFERENCES material_receipt_items(tenant_id, id),
    CONSTRAINT fk_work_order_material_movements_batch
        FOREIGN KEY (tenant_id, source_batch_id) REFERENCES production_batches(tenant_id, id),
    CONSTRAINT fk_work_order_material_movements_reservation
        FOREIGN KEY (tenant_id, reservation_id) REFERENCES material_reservations(tenant_id, id),
    CONSTRAINT fk_work_order_material_movements_relationship
        FOREIGN KEY (tenant_id, batch_relationship_id) REFERENCES batch_relationships(tenant_id, id),
    CONSTRAINT fk_work_order_material_movements_uom FOREIGN KEY (uom_id) REFERENCES units_of_measure(id)
);

CREATE INDEX ix_work_order_material_movements_order
    ON work_order_material_movements (tenant_id, work_order_id, created_at);

SELECT apply_tenant_standards('material_reservations');
SELECT apply_tenant_standards('work_order_material_movements');

CREATE TRIGGER trg_append_only BEFORE UPDATE OR DELETE ON work_order_material_movements
    FOR EACH ROW EXECUTE FUNCTION refuse_history_change();

-- A reservation is closed, not deleted, and only its open quantity moves, downwards.
CREATE FUNCTION protect_reservation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'reservation % is stock history and cannot be deleted', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.work_order_id IS DISTINCT FROM OLD.work_order_id
        OR NEW.work_order_material_id IS DISTINCT FROM OLD.work_order_material_id
        OR NEW.lot_id IS DISTINCT FROM OLD.lot_id
        OR NEW.quantity IS DISTINCT FROM OLD.quantity
        OR NEW.uom_id IS DISTINCT FROM OLD.uom_id
        OR NEW.open_quantity > OLD.open_quantity THEN
        RAISE EXCEPTION 'reservation % can only be used or released', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_protect_reservation BEFORE UPDATE OR DELETE ON material_reservations
    FOR EACH ROW EXECUTE FUNCTION protect_reservation();

INSERT INTO permissions (code, module, description) VALUES
    ('WORK_ORDER_MATERIAL_RESERVE', 'Manufacturing', 'Reserve stock for work orders and release reservations'),
    ('WORK_ORDER_MATERIAL_ISSUE', 'Manufacturing', 'Issue material to work orders and return what was not used');

ALTER TABLE roles NO FORCE ROW LEVEL SECURITY;
ALTER TABLE role_permissions NO FORCE ROW LEVEL SECURITY;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r CROSS JOIN permissions p
WHERE r.tenant_id IS NULL
  AND (
    (r.code IN ('ORGANISATION_OWNER', 'ORGANISATION_ADMIN', 'PLANT_MANAGER', 'PRODUCTION_MANAGER')
        AND p.code IN ('WORK_ORDER_MATERIAL_RESERVE', 'WORK_ORDER_MATERIAL_ISSUE'))
    OR (r.code = 'PRODUCTION_PLANNER' AND p.code = 'WORK_ORDER_MATERIAL_RESERVE')
  );

ALTER TABLE roles FORCE ROW LEVEL SECURITY;
ALTER TABLE role_permissions FORCE ROW LEVEL SECURITY;
