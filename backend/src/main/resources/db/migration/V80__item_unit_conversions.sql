-- How much of an item's own (stock) unit one of another unit holds, for that item alone: a drum of glycerin is
-- 200 KG, a litre of it 1.26 KG, a box of these vials 12 PCS. Shared units convert by kind (GM to KG) for every
-- item; these rules add what only holds for one material or product. A rule never changes, so quantities converted
-- with it keep meaning what they did.
CREATE TABLE item_unit_conversions (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    item_kind text NOT NULL,
    item_id uuid NOT NULL,
    uom_id uuid NOT NULL,
    -- One of uom_id holds this many of the item's stock unit.
    factor numeric(24,12) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT item_unit_conversions_pkey PRIMARY KEY (id),
    CONSTRAINT uq_item_unit_conversions UNIQUE (tenant_id, item_id, uom_id),
    CONSTRAINT ck_item_unit_conversions_kind CHECK (item_kind IN ('MATERIAL', 'PRODUCT')),
    CONSTRAINT ck_item_unit_conversions_factor CHECK (factor > 0),
    CONSTRAINT fk_item_unit_conversions_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_item_unit_conversions_uom FOREIGN KEY (uom_id) REFERENCES units_of_measure (id)
);

ALTER TABLE item_unit_conversions ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY item_unit_conversions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON item_unit_conversions
    USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

-- A purchase order line ordered in another unit than the material is kept in, such as 4 DRUM of a material kept in
-- KG: what was ordered, as typed. ordered_quantity stays in the material's unit (800 KG), and its unit price is per
-- ordered unit.
ALTER TABLE purchase_order_items ADD COLUMN entered_quantity numeric(18,6);
ALTER TABLE purchase_order_items ADD COLUMN entered_uom_id uuid REFERENCES units_of_measure (id);
ALTER TABLE purchase_order_items ADD CONSTRAINT ck_purchase_order_items_entered
    CHECK ((entered_quantity IS NULL) = (entered_uom_id IS NULL));

-- A receipt line typed in another unit, as typed; its quantity is in the material's unit.
ALTER TABLE material_receipt_items ADD COLUMN entered_quantity numeric(18,6);
ALTER TABLE material_receipt_items ADD COLUMN entered_uom_id uuid REFERENCES units_of_measure (id);
ALTER TABLE material_receipt_items ADD CONSTRAINT ck_material_receipt_items_entered
    CHECK ((entered_quantity IS NULL) = (entered_uom_id IS NULL));
