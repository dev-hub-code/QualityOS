-- Facilities, suppliers, products, materials, purchase orders, receipts and attachments.
-- ${approle} is the unprivileged role the application connects as.

SET LOCAL check_function_bodies = off;

-- Row-level security and the updated_at trigger for a table with a tenant_id. Use for every new tenant table.
CREATE FUNCTION apply_tenant_standards(target regclass) RETURNS void
    LANGUAGE plpgsql
    AS $$
BEGIN
    EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', target);
    EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', target);
    EXECUTE format('CREATE POLICY tenant_isolation ON %s USING (tenant_id = current_tenant_id()) '
                       || 'WITH CHECK (tenant_id = current_tenant_id())', target);
    EXECUTE format('CREATE TRIGGER trg_updated_at BEFORE UPDATE ON %s '
                       || 'FOR EACH ROW EXECUTE FUNCTION set_updated_at()', target);
END;
$$;

CREATE TABLE attachments (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    entity_type text NOT NULL,
    entity_id uuid NOT NULL,
    category text,
    file_name text NOT NULL,
    content_type text NOT NULL,
    size_bytes bigint NOT NULL,
    sha256 text NOT NULL,
    storage_key text NOT NULL,
    deleted_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL
);

ALTER TABLE ONLY attachments FORCE ROW LEVEL SECURITY;

CREATE TABLE departments (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    facility_id uuid NOT NULL,
    code text NOT NULL,
    name text NOT NULL,
    archived_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL
);

ALTER TABLE ONLY departments FORCE ROW LEVEL SECURITY;

-- Gapless per-tenant document numbers. Rows are locked by the increment until the transaction ends.
CREATE TABLE document_sequences (
    tenant_id uuid NOT NULL,
    sequence_key text NOT NULL,
    last_value bigint NOT NULL
);

ALTER TABLE ONLY document_sequences FORCE ROW LEVEL SECURITY;

CREATE TABLE facilities (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    code text NOT NULL,
    name text NOT NULL,
    address text,
    city text,
    state text,
    postal_code text,
    gstin text,
    archived_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_facilities_gstin CHECK (((gstin IS NULL) OR (gstin ~ '^[0-9]{2}[A-Z0-9]{13}$'::text)))
);

ALTER TABLE ONLY facilities FORCE ROW LEVEL SECURITY;

CREATE TABLE material_receipt_items (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    receipt_id uuid NOT NULL,
    purchase_order_item_id uuid NOT NULL,
    material_id uuid NOT NULL,
    uom_id uuid NOT NULL,
    supplier_batch text NOT NULL,
    internal_batch text NOT NULL,
    quantity numeric(18,6) NOT NULL,
    inspection_required boolean NOT NULL,
    inspection_status text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    disposition text,
    consumed_quantity numeric(18,6) DEFAULT 0 NOT NULL,
    CONSTRAINT ck_receipt_items_consumed CHECK (((consumed_quantity >= (0)::numeric) AND (consumed_quantity <= quantity))),
    CONSTRAINT ck_receipt_items_disposition CHECK ((disposition = ANY (ARRAY['ACCEPT'::text, 'REJECT'::text, 'HOLD'::text, 'REWORK'::text, 'CONDITIONAL_ACCEPTANCE'::text]))),
    CONSTRAINT ck_receipt_items_inspection CHECK ((inspection_status = ANY (ARRAY['NOT_REQUIRED'::text, 'PENDING'::text, 'IN_PROGRESS'::text, 'COMPLETED'::text, 'CANCELLED'::text]))),
    CONSTRAINT ck_receipt_items_quantity CHECK ((quantity > (0)::numeric))
);

ALTER TABLE ONLY material_receipt_items FORCE ROW LEVEL SECURITY;

CREATE TABLE material_receipts (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    grn_number text NOT NULL,
    purchase_order_id uuid NOT NULL,
    supplier_id uuid NOT NULL,
    facility_id uuid NOT NULL,
    received_on date NOT NULL,
    delivery_note text,
    notes text,
    status text DEFAULT 'RECEIVED'::text NOT NULL,
    void_reason text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_material_receipts_status CHECK ((status = ANY (ARRAY['RECEIVED'::text, 'VOIDED'::text])))
);

ALTER TABLE ONLY material_receipts FORCE ROW LEVEL SECURITY;

CREATE TABLE material_specifications (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    material_id uuid NOT NULL,
    name text NOT NULL,
    nominal_value numeric(18,6),
    min_value numeric(18,6),
    max_value numeric(18,6),
    text_value text,
    uom_id uuid,
    notes text,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_material_spec_range CHECK (((min_value IS NULL) OR (max_value IS NULL) OR (min_value <= max_value)))
);

ALTER TABLE ONLY material_specifications FORCE ROW LEVEL SECURITY;

CREATE TABLE materials (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    code text NOT NULL,
    name text NOT NULL,
    description text,
    grade text,
    category_id uuid,
    uom_id uuid NOT NULL,
    inspection_required boolean DEFAULT true NOT NULL,
    properties jsonb DEFAULT '{}'::jsonb NOT NULL,
    archived_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL
);

ALTER TABLE ONLY materials FORCE ROW LEVEL SECURITY;

CREATE TABLE product_categories (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    name text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL
);

ALTER TABLE ONLY product_categories FORCE ROW LEVEL SECURITY;

CREATE TABLE product_materials (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    product_id uuid NOT NULL,
    material_id uuid NOT NULL,
    quantity_per_unit numeric(18,6) NOT NULL,
    uom_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_product_materials_quantity CHECK ((quantity_per_unit > (0)::numeric))
);

ALTER TABLE ONLY product_materials FORCE ROW LEVEL SECURITY;

CREATE TABLE product_revisions (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    product_id uuid NOT NULL,
    revision text NOT NULL,
    change_note text,
    effective_from date NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL
);

ALTER TABLE ONLY product_revisions FORCE ROW LEVEL SECURITY;

CREATE TABLE product_specifications (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    product_id uuid NOT NULL,
    name text NOT NULL,
    nominal_value numeric(18,6),
    min_value numeric(18,6),
    max_value numeric(18,6),
    text_value text,
    uom_id uuid,
    notes text,
    sort_order integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_product_spec_range CHECK (((min_value IS NULL) OR (max_value IS NULL) OR (min_value <= max_value)))
);

ALTER TABLE ONLY product_specifications FORCE ROW LEVEL SECURITY;

CREATE TABLE production_lines (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    facility_id uuid NOT NULL,
    code text NOT NULL,
    name text NOT NULL,
    archived_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL
);

ALTER TABLE ONLY production_lines FORCE ROW LEVEL SECURITY;

CREATE TABLE products (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    code text NOT NULL,
    name text NOT NULL,
    description text,
    product_type text NOT NULL,
    category_id uuid,
    uom_id uuid NOT NULL,
    current_revision text DEFAULT 'A'::text NOT NULL,
    archived_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_products_type CHECK ((product_type = ANY (ARRAY['FINISHED'::text, 'SEMI_FINISHED'::text])))
);

ALTER TABLE ONLY products FORCE ROW LEVEL SECURITY;

CREATE TABLE purchase_order_items (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    purchase_order_id uuid NOT NULL,
    line_number integer NOT NULL,
    material_id uuid NOT NULL,
    uom_id uuid NOT NULL,
    ordered_quantity numeric(18,6) NOT NULL,
    received_quantity numeric(18,6) DEFAULT 0 NOT NULL,
    unit_price numeric(18,4),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_po_items_ordered CHECK ((ordered_quantity > (0)::numeric)),
    CONSTRAINT ck_po_items_received CHECK (((received_quantity >= (0)::numeric) AND (received_quantity <= ordered_quantity)))
);

ALTER TABLE ONLY purchase_order_items FORCE ROW LEVEL SECURITY;

CREATE TABLE purchase_orders (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    po_number text NOT NULL,
    supplier_id uuid NOT NULL,
    facility_id uuid NOT NULL,
    status text DEFAULT 'DRAFT'::text NOT NULL,
    order_date date NOT NULL,
    expected_date date,
    notes text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_purchase_orders_status CHECK ((status = ANY (ARRAY['DRAFT'::text, 'ISSUED'::text, 'PARTIALLY_RECEIVED'::text, 'RECEIVED'::text, 'CLOSED'::text, 'CANCELLED'::text])))
);

ALTER TABLE ONLY purchase_orders FORCE ROW LEVEL SECURITY;

CREATE TABLE shifts (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    facility_id uuid NOT NULL,
    name text NOT NULL,
    starts_at time without time zone NOT NULL,
    ends_at time without time zone NOT NULL,
    archived_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL
);

ALTER TABLE ONLY shifts FORCE ROW LEVEL SECURITY;

CREATE TABLE supplier_categories (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    name text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL
);

ALTER TABLE ONLY supplier_categories FORCE ROW LEVEL SECURITY;

CREATE TABLE supplier_contacts (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    supplier_id uuid NOT NULL,
    name text NOT NULL,
    designation text,
    email text,
    phone text,
    is_primary boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL
);

ALTER TABLE ONLY supplier_contacts FORCE ROW LEVEL SECURITY;

CREATE TABLE suppliers (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    code text NOT NULL,
    name text NOT NULL,
    legal_name text,
    gstin text,
    category_id uuid,
    email text,
    phone text,
    address text,
    city text,
    state text,
    postal_code text,
    country_code text DEFAULT 'IN'::text NOT NULL,
    status text DEFAULT 'DRAFT'::text NOT NULL,
    status_note text,
    approved_by uuid,
    approved_at timestamp with time zone,
    archived_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_suppliers_gstin CHECK (((gstin IS NULL) OR (gstin ~ '^[0-9]{2}[A-Z0-9]{13}$'::text))),
    CONSTRAINT ck_suppliers_status CHECK ((status = ANY (ARRAY['DRAFT'::text, 'PENDING_APPROVAL'::text, 'APPROVED'::text, 'REJECTED'::text, 'BLOCKED'::text])))
);

ALTER TABLE ONLY suppliers FORCE ROW LEVEL SECURITY;

-- tenant_id is NULL for the shared defaults, which tenants can use but not change.
CREATE TABLE units_of_measure (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid,
    code text NOT NULL,
    name text NOT NULL,
    dimension text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL
);

ALTER TABLE ONLY units_of_measure FORCE ROW LEVEL SECURITY;

-- Starting rows, loaded before row-level security is switched on.
INSERT INTO units_of_measure VALUES ('71d2c9c7-034b-45e8-9613-206e7ce3e11e', NULL, 'MM', 'Millimetre', 'Length', '2026-10-31 22:37:39.635073+00', '2026-10-31 22:37:39.635073+00', NULL, NULL, 0);
INSERT INTO units_of_measure VALUES ('f16aeb87-19d4-4def-a3d2-8efb0b9dc211', NULL, 'CM', 'Centimetre', 'Length', '2026-10-31 22:37:39.635073+00', '2026-10-31 22:37:39.635073+00', NULL, NULL, 0);
INSERT INTO units_of_measure VALUES ('f79188cc-bc51-4686-86d1-606392e12f4e', NULL, 'M', 'Metre', 'Length', '2026-10-31 22:37:39.635073+00', '2026-10-31 22:37:39.635073+00', NULL, NULL, 0);
INSERT INTO units_of_measure VALUES ('aa91e891-f069-4a34-9a71-cc6f13cf7e66', NULL, 'KG', 'Kilogram', 'Mass', '2026-10-31 22:37:39.635073+00', '2026-10-31 22:37:39.635073+00', NULL, NULL, 0);
INSERT INTO units_of_measure VALUES ('176ab7a8-4ebf-4d05-ba87-6984750c4eb4', NULL, 'GM', 'Gram', 'Mass', '2026-10-31 22:37:39.635073+00', '2026-10-31 22:37:39.635073+00', NULL, NULL, 0);
INSERT INTO units_of_measure VALUES ('13d567dd-d81a-4bdc-8c33-c8c249ef53c6', NULL, 'LTR', 'Litre', 'Volume', '2026-10-31 22:37:39.635073+00', '2026-10-31 22:37:39.635073+00', NULL, NULL, 0);
INSERT INTO units_of_measure VALUES ('9cfe612a-8d19-4fef-a259-354fcee95bbe', NULL, 'ML', 'Millilitre', 'Volume', '2026-10-31 22:37:39.635073+00', '2026-10-31 22:37:39.635073+00', NULL, NULL, 0);
INSERT INTO units_of_measure VALUES ('535d4233-b895-492f-969e-1d489ddc74e1', NULL, 'PCS', 'Pieces', 'Count', '2026-10-31 22:37:39.635073+00', '2026-10-31 22:37:39.635073+00', NULL, NULL, 0);
INSERT INTO units_of_measure VALUES ('bd19c855-d433-43fd-beff-15091af21dd2', NULL, 'HB', 'Brinell', 'Hardness', '2026-10-31 22:37:39.635073+00', '2026-10-31 22:37:39.635073+00', NULL, NULL, 0);
INSERT INTO units_of_measure VALUES ('cd8fb72e-b452-48cb-8377-15d61ecca136', NULL, 'HRC', 'Rockwell C', 'Hardness', '2026-10-31 22:37:39.635073+00', '2026-10-31 22:37:39.635073+00', NULL, NULL, 0);
INSERT INTO units_of_measure VALUES ('bbef4150-8b35-425c-b3af-e848d0f85523', NULL, '°C', 'Celsius', 'Temperature', '2026-10-31 22:37:39.635073+00', '2026-10-31 22:37:39.635073+00', NULL, NULL, 0);

ALTER TABLE ONLY attachments
    ADD CONSTRAINT attachments_pkey PRIMARY KEY (id);

ALTER TABLE ONLY departments
    ADD CONSTRAINT departments_pkey PRIMARY KEY (id);

ALTER TABLE ONLY document_sequences
    ADD CONSTRAINT document_sequences_pkey PRIMARY KEY (tenant_id, sequence_key);

ALTER TABLE ONLY facilities
    ADD CONSTRAINT facilities_pkey PRIMARY KEY (id);

ALTER TABLE ONLY material_receipt_items
    ADD CONSTRAINT material_receipt_items_pkey PRIMARY KEY (id);

ALTER TABLE ONLY material_receipts
    ADD CONSTRAINT material_receipts_pkey PRIMARY KEY (id);

ALTER TABLE ONLY material_specifications
    ADD CONSTRAINT material_specifications_pkey PRIMARY KEY (id);

ALTER TABLE ONLY materials
    ADD CONSTRAINT materials_pkey PRIMARY KEY (id);

ALTER TABLE ONLY product_categories
    ADD CONSTRAINT product_categories_pkey PRIMARY KEY (id);

ALTER TABLE ONLY product_materials
    ADD CONSTRAINT product_materials_pkey PRIMARY KEY (id);

ALTER TABLE ONLY product_revisions
    ADD CONSTRAINT product_revisions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY product_specifications
    ADD CONSTRAINT product_specifications_pkey PRIMARY KEY (id);

ALTER TABLE ONLY production_lines
    ADD CONSTRAINT production_lines_pkey PRIMARY KEY (id);

ALTER TABLE ONLY products
    ADD CONSTRAINT products_pkey PRIMARY KEY (id);

ALTER TABLE ONLY purchase_order_items
    ADD CONSTRAINT purchase_order_items_pkey PRIMARY KEY (id);

ALTER TABLE ONLY purchase_orders
    ADD CONSTRAINT purchase_orders_pkey PRIMARY KEY (id);

ALTER TABLE ONLY shifts
    ADD CONSTRAINT shifts_pkey PRIMARY KEY (id);

ALTER TABLE ONLY supplier_categories
    ADD CONSTRAINT supplier_categories_pkey PRIMARY KEY (id);

ALTER TABLE ONLY supplier_contacts
    ADD CONSTRAINT supplier_contacts_pkey PRIMARY KEY (id);

ALTER TABLE ONLY suppliers
    ADD CONSTRAINT suppliers_pkey PRIMARY KEY (id);

ALTER TABLE ONLY units_of_measure
    ADD CONSTRAINT units_of_measure_pkey PRIMARY KEY (id);

ALTER TABLE ONLY attachments
    ADD CONSTRAINT uq_attachments_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY departments
    ADD CONSTRAINT uq_departments_code UNIQUE (tenant_id, facility_id, code);

ALTER TABLE ONLY departments
    ADD CONSTRAINT uq_departments_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY facilities
    ADD CONSTRAINT uq_facilities_code UNIQUE (tenant_id, code);

ALTER TABLE ONLY facilities
    ADD CONSTRAINT uq_facilities_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY material_receipt_items
    ADD CONSTRAINT uq_material_receipt_items_batch UNIQUE (tenant_id, internal_batch);

ALTER TABLE ONLY material_receipt_items
    ADD CONSTRAINT uq_material_receipt_items_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY material_receipts
    ADD CONSTRAINT uq_material_receipts_number UNIQUE (tenant_id, grn_number);

ALTER TABLE ONLY material_receipts
    ADD CONSTRAINT uq_material_receipts_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY material_specifications
    ADD CONSTRAINT uq_material_specifications_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY materials
    ADD CONSTRAINT uq_materials_code UNIQUE (tenant_id, code);

ALTER TABLE ONLY materials
    ADD CONSTRAINT uq_materials_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY product_categories
    ADD CONSTRAINT uq_product_categories_name UNIQUE (tenant_id, name);

ALTER TABLE ONLY product_categories
    ADD CONSTRAINT uq_product_categories_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY product_materials
    ADD CONSTRAINT uq_product_materials UNIQUE (tenant_id, product_id, material_id);

ALTER TABLE ONLY product_materials
    ADD CONSTRAINT uq_product_materials_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY product_revisions
    ADD CONSTRAINT uq_product_revisions UNIQUE (tenant_id, product_id, revision);

ALTER TABLE ONLY product_revisions
    ADD CONSTRAINT uq_product_revisions_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY product_specifications
    ADD CONSTRAINT uq_product_specifications_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY production_lines
    ADD CONSTRAINT uq_production_lines_code UNIQUE (tenant_id, facility_id, code);

ALTER TABLE ONLY production_lines
    ADD CONSTRAINT uq_production_lines_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY products
    ADD CONSTRAINT uq_products_code UNIQUE (tenant_id, code);

ALTER TABLE ONLY products
    ADD CONSTRAINT uq_products_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY purchase_order_items
    ADD CONSTRAINT uq_purchase_order_items_line UNIQUE (tenant_id, purchase_order_id, line_number);

ALTER TABLE ONLY purchase_order_items
    ADD CONSTRAINT uq_purchase_order_items_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY purchase_orders
    ADD CONSTRAINT uq_purchase_orders_number UNIQUE (tenant_id, po_number);

ALTER TABLE ONLY purchase_orders
    ADD CONSTRAINT uq_purchase_orders_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY shifts
    ADD CONSTRAINT uq_shifts_name UNIQUE (tenant_id, facility_id, name);

ALTER TABLE ONLY shifts
    ADD CONSTRAINT uq_shifts_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY supplier_categories
    ADD CONSTRAINT uq_supplier_categories_name UNIQUE (tenant_id, name);

ALTER TABLE ONLY supplier_categories
    ADD CONSTRAINT uq_supplier_categories_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY supplier_contacts
    ADD CONSTRAINT uq_supplier_contacts_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY suppliers
    ADD CONSTRAINT uq_suppliers_code UNIQUE (tenant_id, code);

ALTER TABLE ONLY suppliers
    ADD CONSTRAINT uq_suppliers_tenant_id UNIQUE (tenant_id, id);

CREATE INDEX ix_attachments_parent ON attachments USING btree (tenant_id, entity_type, entity_id);

CREATE INDEX ix_material_receipts_po ON material_receipts USING btree (tenant_id, purchase_order_id);

CREATE INDEX ix_material_specifications_material ON material_specifications USING btree (tenant_id, material_id);

CREATE INDEX ix_materials_name_trgm ON materials USING gin (name gin_trgm_ops);

CREATE INDEX ix_product_specifications_product ON product_specifications USING btree (tenant_id, product_id);

CREATE INDEX ix_products_name_trgm ON products USING gin (name gin_trgm_ops);

CREATE INDEX ix_purchase_order_items_material ON purchase_order_items USING btree (tenant_id, material_id);

CREATE INDEX ix_purchase_orders_status ON purchase_orders USING btree (tenant_id, status);

CREATE INDEX ix_purchase_orders_supplier ON purchase_orders USING btree (tenant_id, supplier_id);

CREATE INDEX ix_receipt_items_inspection ON material_receipt_items USING btree (tenant_id, inspection_status);

CREATE INDEX ix_receipt_items_receipt ON material_receipt_items USING btree (tenant_id, receipt_id);

CREATE INDEX ix_receipt_items_supplier_batch ON material_receipt_items USING btree (tenant_id, supplier_batch);

CREATE INDEX ix_supplier_contacts_supplier ON supplier_contacts USING btree (tenant_id, supplier_id);

CREATE INDEX ix_suppliers_name_trgm ON suppliers USING gin (name gin_trgm_ops);

CREATE INDEX ix_suppliers_tenant_status ON suppliers USING btree (tenant_id, status);

CREATE UNIQUE INDEX uq_units_system_code ON units_of_measure USING btree (code) WHERE (tenant_id IS NULL);

CREATE UNIQUE INDEX uq_units_tenant_code ON units_of_measure USING btree (tenant_id, code) WHERE (tenant_id IS NOT NULL);

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON attachments FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON departments FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON facilities FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON material_receipt_items FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON material_receipts FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON material_specifications FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON materials FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON product_categories FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON product_materials FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON product_revisions FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON product_specifications FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON production_lines FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON products FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON purchase_order_items FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON purchase_orders FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON shifts FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON supplier_categories FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON supplier_contacts FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON suppliers FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON units_of_measure FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE ONLY attachments
    ADD CONSTRAINT attachments_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY departments
    ADD CONSTRAINT departments_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY document_sequences
    ADD CONSTRAINT document_sequences_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY facilities
    ADD CONSTRAINT facilities_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY departments
    ADD CONSTRAINT fk_departments_facility_id FOREIGN KEY (tenant_id, facility_id) REFERENCES facilities(tenant_id, id);

ALTER TABLE ONLY material_receipt_items
    ADD CONSTRAINT fk_material_receipt_items_material_id FOREIGN KEY (tenant_id, material_id) REFERENCES materials(tenant_id, id);

ALTER TABLE ONLY material_receipt_items
    ADD CONSTRAINT fk_material_receipt_items_purchase_order_item_id FOREIGN KEY (tenant_id, purchase_order_item_id) REFERENCES purchase_order_items(tenant_id, id);

ALTER TABLE ONLY material_receipt_items
    ADD CONSTRAINT fk_material_receipt_items_receipt_id FOREIGN KEY (tenant_id, receipt_id) REFERENCES material_receipts(tenant_id, id);

ALTER TABLE ONLY material_receipts
    ADD CONSTRAINT fk_material_receipts_facility_id FOREIGN KEY (tenant_id, facility_id) REFERENCES facilities(tenant_id, id);

ALTER TABLE ONLY material_receipts
    ADD CONSTRAINT fk_material_receipts_purchase_order_id FOREIGN KEY (tenant_id, purchase_order_id) REFERENCES purchase_orders(tenant_id, id);

ALTER TABLE ONLY material_receipts
    ADD CONSTRAINT fk_material_receipts_supplier_id FOREIGN KEY (tenant_id, supplier_id) REFERENCES suppliers(tenant_id, id);

ALTER TABLE ONLY material_specifications
    ADD CONSTRAINT fk_material_specifications_material_id FOREIGN KEY (tenant_id, material_id) REFERENCES materials(tenant_id, id);

ALTER TABLE ONLY materials
    ADD CONSTRAINT fk_materials_category_id FOREIGN KEY (tenant_id, category_id) REFERENCES product_categories(tenant_id, id);

ALTER TABLE ONLY product_materials
    ADD CONSTRAINT fk_product_materials_material_id FOREIGN KEY (tenant_id, material_id) REFERENCES materials(tenant_id, id);

ALTER TABLE ONLY product_materials
    ADD CONSTRAINT fk_product_materials_product_id FOREIGN KEY (tenant_id, product_id) REFERENCES products(tenant_id, id);

ALTER TABLE ONLY product_revisions
    ADD CONSTRAINT fk_product_revisions_product_id FOREIGN KEY (tenant_id, product_id) REFERENCES products(tenant_id, id);

ALTER TABLE ONLY product_specifications
    ADD CONSTRAINT fk_product_specifications_product_id FOREIGN KEY (tenant_id, product_id) REFERENCES products(tenant_id, id);

ALTER TABLE ONLY production_lines
    ADD CONSTRAINT fk_production_lines_facility_id FOREIGN KEY (tenant_id, facility_id) REFERENCES facilities(tenant_id, id);

ALTER TABLE ONLY products
    ADD CONSTRAINT fk_products_category_id FOREIGN KEY (tenant_id, category_id) REFERENCES product_categories(tenant_id, id);

ALTER TABLE ONLY purchase_order_items
    ADD CONSTRAINT fk_purchase_order_items_material_id FOREIGN KEY (tenant_id, material_id) REFERENCES materials(tenant_id, id);

ALTER TABLE ONLY purchase_order_items
    ADD CONSTRAINT fk_purchase_order_items_purchase_order_id FOREIGN KEY (tenant_id, purchase_order_id) REFERENCES purchase_orders(tenant_id, id);

ALTER TABLE ONLY purchase_orders
    ADD CONSTRAINT fk_purchase_orders_facility_id FOREIGN KEY (tenant_id, facility_id) REFERENCES facilities(tenant_id, id);

ALTER TABLE ONLY purchase_orders
    ADD CONSTRAINT fk_purchase_orders_supplier_id FOREIGN KEY (tenant_id, supplier_id) REFERENCES suppliers(tenant_id, id);

ALTER TABLE ONLY shifts
    ADD CONSTRAINT fk_shifts_facility_id FOREIGN KEY (tenant_id, facility_id) REFERENCES facilities(tenant_id, id);

ALTER TABLE ONLY supplier_contacts
    ADD CONSTRAINT fk_supplier_contacts_supplier_id FOREIGN KEY (tenant_id, supplier_id) REFERENCES suppliers(tenant_id, id);

ALTER TABLE ONLY suppliers
    ADD CONSTRAINT fk_suppliers_category_id FOREIGN KEY (tenant_id, category_id) REFERENCES supplier_categories(tenant_id, id);

ALTER TABLE ONLY material_receipt_items
    ADD CONSTRAINT material_receipt_items_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY material_receipt_items
    ADD CONSTRAINT material_receipt_items_uom_id_fkey FOREIGN KEY (uom_id) REFERENCES units_of_measure(id);

ALTER TABLE ONLY material_receipts
    ADD CONSTRAINT material_receipts_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY material_specifications
    ADD CONSTRAINT material_specifications_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY material_specifications
    ADD CONSTRAINT material_specifications_uom_id_fkey FOREIGN KEY (uom_id) REFERENCES units_of_measure(id);

ALTER TABLE ONLY materials
    ADD CONSTRAINT materials_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY materials
    ADD CONSTRAINT materials_uom_id_fkey FOREIGN KEY (uom_id) REFERENCES units_of_measure(id);

ALTER TABLE ONLY product_categories
    ADD CONSTRAINT product_categories_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY product_materials
    ADD CONSTRAINT product_materials_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY product_materials
    ADD CONSTRAINT product_materials_uom_id_fkey FOREIGN KEY (uom_id) REFERENCES units_of_measure(id);

ALTER TABLE ONLY product_revisions
    ADD CONSTRAINT product_revisions_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY product_specifications
    ADD CONSTRAINT product_specifications_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY product_specifications
    ADD CONSTRAINT product_specifications_uom_id_fkey FOREIGN KEY (uom_id) REFERENCES units_of_measure(id);

ALTER TABLE ONLY production_lines
    ADD CONSTRAINT production_lines_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY products
    ADD CONSTRAINT products_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY products
    ADD CONSTRAINT products_uom_id_fkey FOREIGN KEY (uom_id) REFERENCES units_of_measure(id);

ALTER TABLE ONLY purchase_order_items
    ADD CONSTRAINT purchase_order_items_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY purchase_order_items
    ADD CONSTRAINT purchase_order_items_uom_id_fkey FOREIGN KEY (uom_id) REFERENCES units_of_measure(id);

ALTER TABLE ONLY purchase_orders
    ADD CONSTRAINT purchase_orders_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY shifts
    ADD CONSTRAINT shifts_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY supplier_categories
    ADD CONSTRAINT supplier_categories_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY supplier_contacts
    ADD CONSTRAINT supplier_contacts_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY suppliers
    ADD CONSTRAINT suppliers_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY units_of_measure
    ADD CONSTRAINT units_of_measure_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE attachments ENABLE ROW LEVEL SECURITY;

ALTER TABLE departments ENABLE ROW LEVEL SECURITY;

ALTER TABLE document_sequences ENABLE ROW LEVEL SECURITY;

ALTER TABLE facilities ENABLE ROW LEVEL SECURITY;

ALTER TABLE material_receipt_items ENABLE ROW LEVEL SECURITY;

ALTER TABLE material_receipts ENABLE ROW LEVEL SECURITY;

ALTER TABLE material_specifications ENABLE ROW LEVEL SECURITY;

ALTER TABLE materials ENABLE ROW LEVEL SECURITY;

CREATE POLICY platform_scan ON attachments FOR SELECT TO "${flyway:user}" USING (true);

CREATE POLICY platform_scan ON facilities FOR SELECT TO "${flyway:user}" USING (true);

ALTER TABLE product_categories ENABLE ROW LEVEL SECURITY;

ALTER TABLE product_materials ENABLE ROW LEVEL SECURITY;

ALTER TABLE product_revisions ENABLE ROW LEVEL SECURITY;

ALTER TABLE product_specifications ENABLE ROW LEVEL SECURITY;

ALTER TABLE production_lines ENABLE ROW LEVEL SECURITY;

ALTER TABLE products ENABLE ROW LEVEL SECURITY;

ALTER TABLE purchase_order_items ENABLE ROW LEVEL SECURITY;

ALTER TABLE purchase_orders ENABLE ROW LEVEL SECURITY;

ALTER TABLE shifts ENABLE ROW LEVEL SECURITY;

ALTER TABLE supplier_categories ENABLE ROW LEVEL SECURITY;

ALTER TABLE supplier_contacts ENABLE ROW LEVEL SECURITY;

ALTER TABLE suppliers ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON attachments USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON departments USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON document_sequences USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON facilities USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON material_receipt_items USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON material_receipts USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON material_specifications USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON materials USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON product_categories USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON product_materials USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON product_revisions USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON product_specifications USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON production_lines USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON products USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON purchase_order_items USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON purchase_orders USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON shifts USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON supplier_categories USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON supplier_contacts USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON suppliers USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON units_of_measure USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

ALTER TABLE units_of_measure ENABLE ROW LEVEL SECURITY;

CREATE POLICY units_system_read ON units_of_measure FOR SELECT USING ((tenant_id IS NULL));

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE attachments TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE departments TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE document_sequences TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE facilities TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE material_receipt_items TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE material_receipts TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE material_specifications TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE materials TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE product_categories TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE product_materials TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE product_revisions TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE product_specifications TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE production_lines TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE products TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE purchase_order_items TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE purchase_orders TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE shifts TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE supplier_categories TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE supplier_contacts TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE suppliers TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE units_of_measure TO ${approle};
