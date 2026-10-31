-- Production batches, genealogy and dispatch. Genealogy and dispatch rows are append-only: a trace is only worth
-- anything if what it reads cannot be edited afterwards.

ALTER TABLE material_receipt_items ADD COLUMN consumed_quantity NUMERIC(18, 6) NOT NULL DEFAULT 0;
ALTER TABLE material_receipt_items ADD CONSTRAINT ck_receipt_items_consumed
    CHECK (consumed_quantity >= 0 AND consumed_quantity <= quantity);

CREATE TABLE customers (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    code TEXT NOT NULL,
    name TEXT NOT NULL,
    gstin TEXT,
    archived_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_customers_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_customers_code UNIQUE (tenant_id, code)
);
SELECT apply_tenant_standards('customers');

CREATE TABLE production_batches (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    batch_number TEXT NOT NULL,
    product_id UUID NOT NULL,
    facility_id UUID NOT NULL,
    status TEXT NOT NULL DEFAULT 'IN_PROGRESS',
    planned_quantity NUMERIC(18, 6) NOT NULL,
    produced_quantity NUMERIC(18, 6),
    used_quantity NUMERIC(18, 6) NOT NULL DEFAULT 0,
    dispatched_quantity NUMERIC(18, 6) NOT NULL DEFAULT 0,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_production_batches_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_production_batches_number UNIQUE (tenant_id, batch_number),
    CONSTRAINT fk_production_batches_product FOREIGN KEY (tenant_id, product_id) REFERENCES products (tenant_id, id),
    CONSTRAINT fk_production_batches_facility FOREIGN KEY (tenant_id, facility_id) REFERENCES facilities (tenant_id, id),
    CONSTRAINT ck_production_batches_status CHECK (status IN
        ('IN_PROGRESS', 'COMPLETED', 'ON_HOLD', 'RELEASED', 'REJECTED')),
    CONSTRAINT ck_production_batches_planned CHECK (planned_quantity > 0),
    -- Nothing can leave a batch beyond what was made, and nothing leaves before release.
    CONSTRAINT ck_production_batches_balance CHECK (used_quantity + dispatched_quantity <= COALESCE(produced_quantity, 0)),
    CONSTRAINT ck_production_batches_release CHECK (status = 'RELEASED' OR (used_quantity = 0 AND dispatched_quantity = 0))
);
CREATE INDEX ix_production_batches_product ON production_batches (tenant_id, product_id);
SELECT apply_tenant_standards('production_batches');

-- A directed graph, walked with recursive queries. Each edge starts at a received lot or an earlier batch.
CREATE TABLE batch_relationships (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    parent_lot_id UUID,
    parent_batch_id UUID,
    child_batch_id UUID NOT NULL,
    quantity NUMERIC(18, 6) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_batch_rel_lot FOREIGN KEY (tenant_id, parent_lot_id) REFERENCES material_receipt_items (tenant_id, id),
    CONSTRAINT fk_batch_rel_parent FOREIGN KEY (tenant_id, parent_batch_id) REFERENCES production_batches (tenant_id, id),
    CONSTRAINT fk_batch_rel_child FOREIGN KEY (tenant_id, child_batch_id) REFERENCES production_batches (tenant_id, id),
    CONSTRAINT ck_batch_rel_one_parent CHECK (num_nonnulls(parent_lot_id, parent_batch_id) = 1),
    CONSTRAINT ck_batch_rel_not_self CHECK (parent_batch_id IS NULL OR parent_batch_id <> child_batch_id),
    CONSTRAINT ck_batch_rel_quantity CHECK (quantity > 0)
);
CREATE INDEX ix_batch_rel_lot ON batch_relationships (tenant_id, parent_lot_id);
CREATE INDEX ix_batch_rel_parent ON batch_relationships (tenant_id, parent_batch_id);
CREATE INDEX ix_batch_rel_child ON batch_relationships (tenant_id, child_batch_id);
SELECT apply_tenant_standards('batch_relationships');

CREATE TABLE dispatch_records (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    dispatch_number TEXT NOT NULL,
    customer_id UUID NOT NULL,
    dispatched_on DATE NOT NULL,
    reference TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_dispatch_records_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_dispatch_records_number UNIQUE (tenant_id, dispatch_number),
    CONSTRAINT fk_dispatch_customer FOREIGN KEY (tenant_id, customer_id) REFERENCES customers (tenant_id, id)
);
SELECT apply_tenant_standards('dispatch_records');

CREATE TABLE dispatch_items (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    dispatch_id UUID NOT NULL,
    production_batch_id UUID NOT NULL,
    quantity NUMERIC(18, 6) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_dispatch_items_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_dispatch_items_dispatch FOREIGN KEY (tenant_id, dispatch_id) REFERENCES dispatch_records (tenant_id, id),
    CONSTRAINT fk_dispatch_items_batch FOREIGN KEY (tenant_id, production_batch_id) REFERENCES production_batches (tenant_id, id),
    CONSTRAINT ck_dispatch_items_quantity CHECK (quantity > 0)
);
CREATE INDEX ix_dispatch_items_batch ON dispatch_items (tenant_id, production_batch_id);
SELECT apply_tenant_standards('dispatch_items');

CREATE TABLE finished_products (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    serial_number TEXT NOT NULL,
    production_batch_id UUID NOT NULL,
    dispatch_item_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_finished_products_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_finished_products_serial UNIQUE (tenant_id, serial_number),
    CONSTRAINT fk_finished_batch FOREIGN KEY (tenant_id, production_batch_id) REFERENCES production_batches (tenant_id, id),
    CONSTRAINT fk_finished_dispatch FOREIGN KEY (tenant_id, dispatch_item_id) REFERENCES dispatch_items (tenant_id, id)
);
CREATE INDEX ix_finished_products_batch ON finished_products (tenant_id, production_batch_id);
SELECT apply_tenant_standards('finished_products');

CREATE TRIGGER trg_append_only BEFORE UPDATE OR DELETE ON batch_relationships
    FOR EACH ROW EXECUTE FUNCTION reject_change();
CREATE TRIGGER trg_append_only BEFORE UPDATE OR DELETE ON dispatch_records
    FOR EACH ROW EXECUTE FUNCTION reject_change();
CREATE TRIGGER trg_append_only BEFORE UPDATE OR DELETE ON dispatch_items
    FOR EACH ROW EXECUTE FUNCTION reject_change();

-- Inspections can now be of a production batch, which has no supplier or material of its own.
ALTER TABLE inspections ALTER COLUMN receipt_item_id DROP NOT NULL;
ALTER TABLE inspections ALTER COLUMN supplier_id DROP NOT NULL;
ALTER TABLE inspections ALTER COLUMN material_id DROP NOT NULL;
ALTER TABLE inspections ADD COLUMN production_batch_id UUID;
ALTER TABLE inspections ADD CONSTRAINT fk_inspections_production_batch
    FOREIGN KEY (tenant_id, production_batch_id) REFERENCES production_batches (tenant_id, id);
ALTER TABLE inspections ADD CONSTRAINT ck_inspections_subject
    CHECK (num_nonnulls(receipt_item_id, production_batch_id) = 1);
CREATE UNIQUE INDEX uq_inspections_active_batch ON inspections (tenant_id, production_batch_id)
    WHERE production_batch_id IS NOT NULL AND status NOT IN ('CANCELLED', 'COMPLETED');
