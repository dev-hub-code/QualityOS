-- Facilities, suppliers, products, materials, purchasing and receipts, plus shared numbering and attachments.
-- Foreign keys between tenant-owned tables include tenant_id, so a row cannot reference another tenant's data.

-- Row-level security and the updated_at trigger for a table with a tenant_id. Use for every new tenant table.
CREATE FUNCTION apply_tenant_standards(target regclass) RETURNS void
    LANGUAGE plpgsql AS
$$
BEGIN
    EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', target);
    EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', target);
    EXECUTE format('CREATE POLICY tenant_isolation ON %s USING (tenant_id = current_tenant_id()) '
                       || 'WITH CHECK (tenant_id = current_tenant_id())', target);
    EXECUTE format('CREATE TRIGGER trg_updated_at BEFORE UPDATE ON %s '
                       || 'FOR EACH ROW EXECUTE FUNCTION set_updated_at()', target);
END;
$$;

-- Gapless per-tenant document numbers. Rows are locked by the increment until the transaction ends.
CREATE TABLE document_sequences (
    tenant_id    UUID   NOT NULL REFERENCES tenants (id),
    sequence_key TEXT   NOT NULL,
    last_value   BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, sequence_key)
);
ALTER TABLE document_sequences ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_sequences FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON document_sequences
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());

CREATE TABLE attachments (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    entity_type TEXT NOT NULL,
    entity_id UUID NOT NULL,
    category TEXT,
    file_name TEXT NOT NULL,
    content_type TEXT NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 TEXT NOT NULL,
    storage_key TEXT NOT NULL,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version    BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_attachments_tenant_id UNIQUE (tenant_id, id)
);
CREATE INDEX ix_attachments_parent ON attachments (tenant_id, entity_type, entity_id);
SELECT apply_tenant_standards('attachments');

CREATE TABLE facilities (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    code TEXT NOT NULL,
    name TEXT NOT NULL,
    address TEXT,
    city TEXT,
    state TEXT,
    postal_code TEXT,
    gstin TEXT,
    archived_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version    BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_facilities_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_facilities_code UNIQUE (tenant_id, code),
    CONSTRAINT ck_facilities_gstin CHECK (gstin IS NULL OR gstin ~ '^[0-9]{2}[A-Z0-9]{13}$')
);
SELECT apply_tenant_standards('facilities');

CREATE TABLE departments (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    facility_id UUID NOT NULL,
    code TEXT NOT NULL,
    name TEXT NOT NULL,
    archived_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version    BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_departments_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_departments_facility_id FOREIGN KEY (tenant_id, facility_id) REFERENCES facilities (tenant_id, id),
    CONSTRAINT uq_departments_code UNIQUE (tenant_id, facility_id, code)
);
SELECT apply_tenant_standards('departments');

CREATE TABLE production_lines (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    facility_id UUID NOT NULL,
    code TEXT NOT NULL,
    name TEXT NOT NULL,
    archived_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version    BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_production_lines_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_production_lines_facility_id FOREIGN KEY (tenant_id, facility_id) REFERENCES facilities (tenant_id, id),
    CONSTRAINT uq_production_lines_code UNIQUE (tenant_id, facility_id, code)
);
SELECT apply_tenant_standards('production_lines');

CREATE TABLE shifts (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    facility_id UUID NOT NULL,
    name TEXT NOT NULL,
    starts_at TIME NOT NULL,
    ends_at TIME NOT NULL,
    archived_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version    BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_shifts_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_shifts_facility_id FOREIGN KEY (tenant_id, facility_id) REFERENCES facilities (tenant_id, id),
    CONSTRAINT uq_shifts_name UNIQUE (tenant_id, facility_id, name)
);
SELECT apply_tenant_standards('shifts');

CREATE TABLE supplier_categories (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version    BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_supplier_categories_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_supplier_categories_name UNIQUE (tenant_id, name)
);
SELECT apply_tenant_standards('supplier_categories');

CREATE TABLE suppliers (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    code TEXT NOT NULL,
    name TEXT NOT NULL,
    legal_name TEXT,
    gstin TEXT,
    category_id UUID,
    email TEXT,
    phone TEXT,
    address TEXT,
    city TEXT,
    state TEXT,
    postal_code TEXT,
    country_code TEXT NOT NULL DEFAULT 'IN',
    status TEXT NOT NULL DEFAULT 'DRAFT',
    status_note TEXT,
    approved_by UUID,
    approved_at TIMESTAMPTZ,
    archived_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version    BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_suppliers_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_suppliers_code UNIQUE (tenant_id, code),
    CONSTRAINT fk_suppliers_category_id FOREIGN KEY (tenant_id, category_id) REFERENCES supplier_categories (tenant_id, id),
    CONSTRAINT ck_suppliers_status CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'BLOCKED')),
    CONSTRAINT ck_suppliers_gstin CHECK (gstin IS NULL OR gstin ~ '^[0-9]{2}[A-Z0-9]{13}$')
);
CREATE INDEX ix_suppliers_tenant_status ON suppliers (tenant_id, status);
CREATE INDEX ix_suppliers_name_trgm ON suppliers USING gin (name gin_trgm_ops);
SELECT apply_tenant_standards('suppliers');

CREATE TABLE supplier_contacts (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    supplier_id UUID NOT NULL,
    name TEXT NOT NULL,
    designation TEXT,
    email TEXT,
    phone TEXT,
    is_primary BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version    BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_supplier_contacts_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_supplier_contacts_supplier_id FOREIGN KEY (tenant_id, supplier_id) REFERENCES suppliers (tenant_id, id)
);
CREATE INDEX ix_supplier_contacts_supplier ON supplier_contacts (tenant_id, supplier_id);
SELECT apply_tenant_standards('supplier_contacts');

-- tenant_id is NULL for the shared defaults, which tenants can use but not change.
CREATE TABLE units_of_measure (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID REFERENCES tenants (id),
    code       TEXT NOT NULL,
    name       TEXT NOT NULL,
    dimension  TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version    BIGINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_units_system_code ON units_of_measure (code) WHERE tenant_id IS NULL;
CREATE UNIQUE INDEX uq_units_tenant_code ON units_of_measure (tenant_id, code) WHERE tenant_id IS NOT NULL;

INSERT INTO units_of_measure (code, name, dimension) VALUES
    ('MM',  'Millimetre',  'Length'),
    ('CM',  'Centimetre',  'Length'),
    ('M',   'Metre',       'Length'),
    ('KG',  'Kilogram',    'Mass'),
    ('GM',  'Gram',        'Mass'),
    ('LTR', 'Litre',       'Volume'),
    ('ML',  'Millilitre',  'Volume'),
    ('PCS', 'Pieces',      'Count'),
    ('HB',  'Brinell',     'Hardness'),
    ('HRC', 'Rockwell C',  'Hardness'),
    ('°C',  'Celsius',     'Temperature');

ALTER TABLE units_of_measure ENABLE ROW LEVEL SECURITY;
ALTER TABLE units_of_measure FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON units_of_measure
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY units_system_read ON units_of_measure FOR SELECT USING (tenant_id IS NULL);
CREATE TRIGGER trg_updated_at BEFORE UPDATE ON units_of_measure
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE product_categories (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version    BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_product_categories_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_product_categories_name UNIQUE (tenant_id, name)
);
SELECT apply_tenant_standards('product_categories');

CREATE TABLE materials (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    code TEXT NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    grade TEXT,
    category_id UUID,
    uom_id UUID NOT NULL REFERENCES units_of_measure (id),
    inspection_required BOOLEAN NOT NULL DEFAULT true,
    properties JSONB NOT NULL DEFAULT '{}',
    archived_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version    BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_materials_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_materials_code UNIQUE (tenant_id, code),
    CONSTRAINT fk_materials_category_id FOREIGN KEY (tenant_id, category_id) REFERENCES product_categories (tenant_id, id)
);
CREATE INDEX ix_materials_name_trgm ON materials USING gin (name gin_trgm_ops);
SELECT apply_tenant_standards('materials');

CREATE TABLE material_specifications (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    material_id UUID NOT NULL,
    name TEXT NOT NULL,
    nominal_value NUMERIC(18, 6),
    min_value NUMERIC(18, 6),
    max_value NUMERIC(18, 6),
    text_value TEXT,
    uom_id UUID REFERENCES units_of_measure (id),
    notes TEXT,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version    BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_material_specifications_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_material_specifications_material_id FOREIGN KEY (tenant_id, material_id) REFERENCES materials (tenant_id, id),
    CONSTRAINT ck_material_spec_range CHECK (min_value IS NULL OR max_value IS NULL OR min_value <= max_value)
);
CREATE INDEX ix_material_specifications_material ON material_specifications (tenant_id, material_id);
SELECT apply_tenant_standards('material_specifications');

CREATE TABLE products (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    code TEXT NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    product_type TEXT NOT NULL,
    category_id UUID,
    uom_id UUID NOT NULL REFERENCES units_of_measure (id),
    current_revision TEXT NOT NULL DEFAULT 'A',
    archived_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version    BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_products_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_products_code UNIQUE (tenant_id, code),
    CONSTRAINT fk_products_category_id FOREIGN KEY (tenant_id, category_id) REFERENCES product_categories (tenant_id, id),
    CONSTRAINT ck_products_type CHECK (product_type IN ('FINISHED', 'SEMI_FINISHED'))
);
CREATE INDEX ix_products_name_trgm ON products USING gin (name gin_trgm_ops);
SELECT apply_tenant_standards('products');

CREATE TABLE product_revisions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    product_id UUID NOT NULL,
    revision TEXT NOT NULL,
    change_note TEXT,
    effective_from DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version    BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_product_revisions_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_product_revisions_product_id FOREIGN KEY (tenant_id, product_id) REFERENCES products (tenant_id, id),
    CONSTRAINT uq_product_revisions UNIQUE (tenant_id, product_id, revision)
);
SELECT apply_tenant_standards('product_revisions');

CREATE TABLE product_specifications (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    product_id UUID NOT NULL,
    name TEXT NOT NULL,
    nominal_value NUMERIC(18, 6),
    min_value NUMERIC(18, 6),
    max_value NUMERIC(18, 6),
    text_value TEXT,
    uom_id UUID REFERENCES units_of_measure (id),
    notes TEXT,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version    BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_product_specifications_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_product_specifications_product_id FOREIGN KEY (tenant_id, product_id) REFERENCES products (tenant_id, id),
    CONSTRAINT ck_product_spec_range CHECK (min_value IS NULL OR max_value IS NULL OR min_value <= max_value)
);
CREATE INDEX ix_product_specifications_product ON product_specifications (tenant_id, product_id);
SELECT apply_tenant_standards('product_specifications');

CREATE TABLE product_materials (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    product_id UUID NOT NULL,
    material_id UUID NOT NULL,
    quantity_per_unit NUMERIC(18, 6) NOT NULL,
    uom_id UUID NOT NULL REFERENCES units_of_measure (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version    BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_product_materials_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_product_materials_product_id FOREIGN KEY (tenant_id, product_id) REFERENCES products (tenant_id, id),
    CONSTRAINT fk_product_materials_material_id FOREIGN KEY (tenant_id, material_id) REFERENCES materials (tenant_id, id),
    CONSTRAINT uq_product_materials UNIQUE (tenant_id, product_id, material_id),
    CONSTRAINT ck_product_materials_quantity CHECK (quantity_per_unit > 0)
);
SELECT apply_tenant_standards('product_materials');

CREATE TABLE purchase_orders (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    po_number TEXT NOT NULL,
    supplier_id UUID NOT NULL,
    facility_id UUID NOT NULL,
    status TEXT NOT NULL DEFAULT 'DRAFT',
    order_date DATE NOT NULL,
    expected_date DATE,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version    BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_purchase_orders_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_purchase_orders_number UNIQUE (tenant_id, po_number),
    CONSTRAINT fk_purchase_orders_supplier_id FOREIGN KEY (tenant_id, supplier_id) REFERENCES suppliers (tenant_id, id),
    CONSTRAINT fk_purchase_orders_facility_id FOREIGN KEY (tenant_id, facility_id) REFERENCES facilities (tenant_id, id),
    CONSTRAINT ck_purchase_orders_status CHECK (status IN ('DRAFT', 'ISSUED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CLOSED', 'CANCELLED'))
);
CREATE INDEX ix_purchase_orders_supplier ON purchase_orders (tenant_id, supplier_id);
CREATE INDEX ix_purchase_orders_status ON purchase_orders (tenant_id, status);
SELECT apply_tenant_standards('purchase_orders');

CREATE TABLE purchase_order_items (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    purchase_order_id UUID NOT NULL,
    line_number INTEGER NOT NULL,
    material_id UUID NOT NULL,
    uom_id UUID NOT NULL REFERENCES units_of_measure (id),
    ordered_quantity NUMERIC(18, 6) NOT NULL,
    received_quantity NUMERIC(18, 6) NOT NULL DEFAULT 0,
    unit_price NUMERIC(18, 4),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version    BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_purchase_order_items_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_purchase_order_items_purchase_order_id FOREIGN KEY (tenant_id, purchase_order_id) REFERENCES purchase_orders (tenant_id, id),
    CONSTRAINT fk_purchase_order_items_material_id FOREIGN KEY (tenant_id, material_id) REFERENCES materials (tenant_id, id),
    CONSTRAINT uq_purchase_order_items_line UNIQUE (tenant_id, purchase_order_id, line_number),
    CONSTRAINT ck_po_items_ordered CHECK (ordered_quantity > 0),
    CONSTRAINT ck_po_items_received CHECK (received_quantity >= 0 AND received_quantity <= ordered_quantity)
);
CREATE INDEX ix_purchase_order_items_material ON purchase_order_items (tenant_id, material_id);
SELECT apply_tenant_standards('purchase_order_items');

CREATE TABLE material_receipts (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    grn_number TEXT NOT NULL,
    purchase_order_id UUID NOT NULL,
    supplier_id UUID NOT NULL,
    facility_id UUID NOT NULL,
    received_on DATE NOT NULL,
    delivery_note TEXT,
    notes TEXT,
    status TEXT NOT NULL DEFAULT 'RECEIVED',
    void_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version    BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_material_receipts_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_material_receipts_number UNIQUE (tenant_id, grn_number),
    CONSTRAINT fk_material_receipts_purchase_order_id FOREIGN KEY (tenant_id, purchase_order_id) REFERENCES purchase_orders (tenant_id, id),
    CONSTRAINT fk_material_receipts_supplier_id FOREIGN KEY (tenant_id, supplier_id) REFERENCES suppliers (tenant_id, id),
    CONSTRAINT fk_material_receipts_facility_id FOREIGN KEY (tenant_id, facility_id) REFERENCES facilities (tenant_id, id),
    CONSTRAINT ck_material_receipts_status CHECK (status IN ('RECEIVED', 'VOIDED'))
);
CREATE INDEX ix_material_receipts_po ON material_receipts (tenant_id, purchase_order_id);
SELECT apply_tenant_standards('material_receipts');

CREATE TABLE material_receipt_items (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    receipt_id UUID NOT NULL,
    purchase_order_item_id UUID NOT NULL,
    material_id UUID NOT NULL,
    uom_id UUID NOT NULL REFERENCES units_of_measure (id),
    supplier_batch TEXT NOT NULL,
    internal_batch TEXT NOT NULL,
    quantity NUMERIC(18, 6) NOT NULL,
    inspection_required BOOLEAN NOT NULL,
    inspection_status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version    BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_material_receipt_items_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_material_receipt_items_receipt_id FOREIGN KEY (tenant_id, receipt_id) REFERENCES material_receipts (tenant_id, id),
    CONSTRAINT fk_material_receipt_items_purchase_order_item_id FOREIGN KEY (tenant_id, purchase_order_item_id) REFERENCES purchase_order_items (tenant_id, id),
    CONSTRAINT fk_material_receipt_items_material_id FOREIGN KEY (tenant_id, material_id) REFERENCES materials (tenant_id, id),
    CONSTRAINT uq_material_receipt_items_batch UNIQUE (tenant_id, internal_batch),
    CONSTRAINT ck_receipt_items_quantity CHECK (quantity > 0),
    CONSTRAINT ck_receipt_items_inspection CHECK (inspection_status IN ('NOT_REQUIRED', 'PENDING', 'CANCELLED'))
);
CREATE INDEX ix_receipt_items_receipt ON material_receipt_items (tenant_id, receipt_id);
CREATE INDEX ix_receipt_items_supplier_batch ON material_receipt_items (tenant_id, supplier_batch);
SELECT apply_tenant_standards('material_receipt_items');
