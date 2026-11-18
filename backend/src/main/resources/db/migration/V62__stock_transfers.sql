-- Moving stock between plants. What is sent leaves its lot or batch at once, and is in transit until the other plant
-- receives it. On receipt it becomes a lot or batch of its own there, which names where it came from, so traces,
-- recalls and holds follow it.
CREATE TABLE stock_transfers (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    transfer_number text NOT NULL,
    from_facility_id uuid NOT NULL,
    to_facility_id uuid NOT NULL,
    status text NOT NULL DEFAULT 'IN_TRANSIT',
    sent_on date NOT NULL,
    received_on date,
    received_by uuid,
    note text,
    cancel_reason text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT stock_transfers_pkey PRIMARY KEY (id),
    CONSTRAINT uq_stock_transfers_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_stock_transfers_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_stock_transfers_from FOREIGN KEY (tenant_id, from_facility_id) REFERENCES facilities (tenant_id, id),
    CONSTRAINT fk_stock_transfers_to FOREIGN KEY (tenant_id, to_facility_id) REFERENCES facilities (tenant_id, id),
    CONSTRAINT ck_stock_transfers_status CHECK (status IN ('IN_TRANSIT', 'RECEIVED', 'CANCELLED')),
    CONSTRAINT ck_stock_transfers_places CHECK (from_facility_id <> to_facility_id),
    CONSTRAINT ck_stock_transfers_received CHECK ((status = 'RECEIVED') = (received_on IS NOT NULL)),
    CONSTRAINT ck_stock_transfers_cancelled CHECK ((status = 'CANCELLED') = (cancel_reason IS NOT NULL)),
    CONSTRAINT ck_stock_transfers_note CHECK (note IS NULL OR length(note) <= 1000),
    CONSTRAINT ck_stock_transfers_reason CHECK (cancel_reason IS NULL OR length(btrim(cancel_reason)) BETWEEN 1 AND 1000)
);
CREATE UNIQUE INDEX uq_stock_transfers_number ON stock_transfers (tenant_id, transfer_number);
CREATE INDEX ix_stock_transfers_from ON stock_transfers (tenant_id, from_facility_id);
CREATE INDEX ix_stock_transfers_to ON stock_transfers (tenant_id, to_facility_id);
CREATE TRIGGER trg_updated_at BEFORE UPDATE ON stock_transfers FOR EACH ROW EXECUTE FUNCTION set_updated_at();
ALTER TABLE stock_transfers ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY stock_transfers FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON stock_transfers
    USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE TABLE stock_transfer_items (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    transfer_id uuid NOT NULL,
    source_lot_id uuid,
    source_batch_id uuid,
    quantity numeric(18,6) NOT NULL,
    received_lot_id uuid,
    received_batch_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT stock_transfer_items_pkey PRIMARY KEY (id),
    CONSTRAINT fk_stock_transfer_items_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_stock_transfer_items_transfer FOREIGN KEY (tenant_id, transfer_id) REFERENCES stock_transfers (tenant_id, id),
    CONSTRAINT fk_stock_transfer_items_source_lot FOREIGN KEY (tenant_id, source_lot_id) REFERENCES material_receipt_items (tenant_id, id),
    CONSTRAINT fk_stock_transfer_items_source_batch FOREIGN KEY (tenant_id, source_batch_id) REFERENCES production_batches (tenant_id, id),
    CONSTRAINT fk_stock_transfer_items_received_lot FOREIGN KEY (tenant_id, received_lot_id) REFERENCES material_receipt_items (tenant_id, id),
    CONSTRAINT fk_stock_transfer_items_received_batch FOREIGN KEY (tenant_id, received_batch_id) REFERENCES production_batches (tenant_id, id),
    CONSTRAINT ck_stock_transfer_items_source CHECK (num_nonnulls(source_lot_id, source_batch_id) = 1),
    CONSTRAINT ck_stock_transfer_items_received CHECK (received_lot_id IS NULL OR source_lot_id IS NOT NULL),
    CONSTRAINT ck_stock_transfer_items_received_batch CHECK (received_batch_id IS NULL OR source_batch_id IS NOT NULL),
    CONSTRAINT ck_stock_transfer_items_quantity CHECK (quantity > 0)
);
CREATE INDEX ix_stock_transfer_items_transfer ON stock_transfer_items (tenant_id, transfer_id);
CREATE INDEX ix_stock_transfer_items_source_lot ON stock_transfer_items (tenant_id, source_lot_id);
CREATE INDEX ix_stock_transfer_items_source_batch ON stock_transfer_items (tenant_id, source_batch_id);
CREATE INDEX ix_stock_transfer_items_received_lot ON stock_transfer_items (tenant_id, received_lot_id);
CREATE INDEX ix_stock_transfer_items_received_batch ON stock_transfer_items (tenant_id, received_batch_id);
CREATE TRIGGER trg_updated_at BEFORE UPDATE ON stock_transfer_items FOR EACH ROW EXECUTE FUNCTION set_updated_at();
ALTER TABLE stock_transfer_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY stock_transfer_items FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON stock_transfer_items
    USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

-- Stock sent away, in transit or received elsewhere. A lot received from another plant is placed there, not at the
-- plant of its goods receipt, and names the lot it came from.
ALTER TABLE material_receipt_items
    ADD COLUMN transferred_quantity numeric(18,6) NOT NULL DEFAULT 0,
    ADD COLUMN facility_id uuid,
    ADD COLUMN transferred_from_id uuid,
    ADD CONSTRAINT ck_material_receipt_items_transferred CHECK (transferred_quantity >= 0),
    ADD CONSTRAINT ck_material_receipt_items_transfer_place CHECK ((facility_id IS NULL) = (transferred_from_id IS NULL)),
    ADD CONSTRAINT fk_material_receipt_items_facility FOREIGN KEY (tenant_id, facility_id) REFERENCES facilities (tenant_id, id),
    ADD CONSTRAINT fk_material_receipt_items_transferred_from FOREIGN KEY (tenant_id, transferred_from_id)
        REFERENCES material_receipt_items (tenant_id, id);
CREATE INDEX ix_material_receipt_items_facility ON material_receipt_items (tenant_id, facility_id) WHERE facility_id IS NOT NULL;
CREATE INDEX ix_material_receipt_items_transferred_from ON material_receipt_items (tenant_id, transferred_from_id)
    WHERE transferred_from_id IS NOT NULL;

ALTER TABLE production_batches
    ADD COLUMN transferred_quantity numeric(18,6) NOT NULL DEFAULT 0,
    ADD COLUMN transferred_from_id uuid,
    ADD CONSTRAINT ck_production_batches_transferred CHECK (transferred_quantity >= 0),
    ADD CONSTRAINT fk_production_batches_transferred_from FOREIGN KEY (tenant_id, transferred_from_id)
        REFERENCES production_batches (tenant_id, id);
CREATE INDEX ix_production_batches_transferred_from ON production_batches (tenant_id, transferred_from_id)
    WHERE transferred_from_id IS NOT NULL;
