-- Production, dispatch, shipments, returns, corrections, serial numbers and recalls.
-- ${approle} is the unprivileged role the application connects as.

SET LOCAL check_function_bodies = off;

CREATE FUNCTION expire_unverified_accounts(cutoff timestamp with time zone) RETURNS integer
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
DECLARE
    expired UUID[];
BEGIN
    SELECT coalesce(array_agg(id), '{}') INTO expired FROM users
    WHERE status = 'PENDING_VERIFICATION' AND created_at < cutoff;

    UPDATE tenants t SET status = 'SUSPENDED'
    WHERE t.id IN (SELECT tenant_id FROM tenant_memberships WHERE user_id = ANY (expired))
      AND NOT EXISTS (SELECT 1 FROM tenant_memberships m WHERE m.tenant_id = t.id AND m.user_id <> ALL (expired));
    DELETE FROM refresh_tokens WHERE user_id = ANY (expired);
    DELETE FROM account_tokens WHERE user_id = ANY (expired);
    UPDATE users
    SET status = 'DISABLED', email = 'expired-' || id || '@expired.invalid', full_name = 'Expired sign-up',
        password_hash = '!'
    WHERE id = ANY (expired);
    RETURN cardinality(expired);
END;
$$;

CREATE FUNCTION protect_closed_recall_contact() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM recalls r
               WHERE r.tenant_id = OLD.tenant_id AND r.id = OLD.recall_id AND r.status = 'CLOSED') THEN
        RAISE EXCEPTION 'recall % is closed', OLD.recall_id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE FUNCTION protect_closed_reversal() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF OLD.status IN ('CLOSED', 'CANCELLED') THEN
        RAISE EXCEPTION 'dispatch_reversals % is closed', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION protect_dispatch_return() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION '% % cannot be deleted', TG_TABLE_NAME, OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION protect_finished_correction() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.status IN ('APPLIED', 'REJECTED', 'CANCELLED') THEN
        RAISE EXCEPTION '% % cannot be changed', TG_TABLE_NAME, OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE FUNCTION protect_returned_goods() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF OLD.status IN ('ACCEPTED', 'REJECTED', 'CANCELLED')
        OR NEW.quantity <> OLD.quantity
        OR NEW.dispatch_item_id <> OLD.dispatch_item_id
        OR NEW.serial_numbers <> OLD.serial_numbers THEN
        RAISE EXCEPTION 'dispatch_reversal_items % cannot be changed', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION require_live_dispatch_for_shipment() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM trace_corrections c WHERE c.tenant_id = NEW.tenant_id AND c.dispatch_id = NEW.dispatch_id) THEN
        RAISE EXCEPTION 'dispatch % was cancelled', NEW.dispatch_id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION require_shipment_for_return() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM dispatch_shipments s WHERE s.tenant_id = NEW.tenant_id AND s.dispatch_id = NEW.dispatch_id) THEN
        RAISE EXCEPTION 'dispatch % has not shipped', NEW.dispatch_id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION require_unshipped_for_cancellation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.dispatch_id IS NOT NULL AND (
        EXISTS (SELECT 1 FROM dispatch_shipments s WHERE s.tenant_id = NEW.tenant_id AND s.dispatch_id = NEW.dispatch_id)
        OR EXISTS (SELECT 1 FROM dispatch_reversals r WHERE r.tenant_id = NEW.tenant_id AND r.dispatch_id = NEW.dispatch_id)
    ) THEN
        RAISE EXCEPTION 'dispatch % physically shipped and cannot be cancelled', NEW.dispatch_id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION tenants_with_capas_due() RETURNS SETOF uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
    SELECT DISTINCT tenant_id FROM corrective_actions
    WHERE status NOT IN ('CLOSED', 'CANCELLED') AND target_date < now()::date + 5;
$$;

CREATE FUNCTION tenants_with_unconfirmed_dispatches() RETURNS SETOF uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
    SELECT DISTINCT d.tenant_id FROM dispatch_records d
    WHERE d.dispatched_on < now()::date - 2
      AND NOT EXISTS (SELECT 1 FROM dispatch_shipments s WHERE s.tenant_id = d.tenant_id AND s.dispatch_id = d.id)
      AND NOT EXISTS (SELECT 1 FROM trace_corrections c WHERE c.tenant_id = d.tenant_id AND c.dispatch_id = d.id);
$$;

-- A directed graph, walked with recursive queries. Each edge starts at a received lot or an earlier batch.
CREATE TABLE batch_relationships (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    parent_lot_id uuid,
    parent_batch_id uuid,
    child_batch_id uuid NOT NULL,
    quantity numeric(18,6) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_batch_rel_not_self CHECK (((parent_batch_id IS NULL) OR (parent_batch_id <> child_batch_id))),
    CONSTRAINT ck_batch_rel_one_parent CHECK ((num_nonnulls(parent_lot_id, parent_batch_id) = 1)),
    CONSTRAINT ck_batch_rel_quantity CHECK ((quantity > (0)::numeric))
);

ALTER TABLE ONLY batch_relationships FORCE ROW LEVEL SECURITY;

CREATE TABLE customers (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    code text NOT NULL,
    name text NOT NULL,
    gstin text,
    archived_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    contact_person text,
    email text,
    phone text,
    address text
);

ALTER TABLE ONLY customers FORCE ROW LEVEL SECURITY;

CREATE TABLE dispatch_items (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    dispatch_id uuid NOT NULL,
    production_batch_id uuid NOT NULL,
    quantity numeric(18,6) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_dispatch_items_quantity CHECK ((quantity > (0)::numeric))
);

ALTER TABLE ONLY dispatch_items FORCE ROW LEVEL SECURITY;

CREATE TABLE dispatch_records (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    dispatch_number text NOT NULL,
    customer_id uuid NOT NULL,
    dispatched_on date NOT NULL,
    reference text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL
);

ALTER TABLE ONLY dispatch_records FORCE ROW LEVEL SECURITY;

CREATE TABLE dispatch_reversal_items (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    reversal_id uuid NOT NULL,
    dispatch_item_id uuid NOT NULL,
    production_batch_id uuid NOT NULL,
    quantity numeric(18,6) NOT NULL,
    serial_numbers jsonb DEFAULT '[]'::jsonb NOT NULL,
    status text DEFAULT 'RETURN_RECEIVED'::text NOT NULL,
    disposition text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_dispatch_reversal_items_quantity CHECK ((quantity > (0)::numeric)),
    CONSTRAINT ck_dispatch_reversal_items_status CHECK ((status = ANY (ARRAY['EXPECTED'::text, 'RETURN_RECEIVED'::text, 'PENDING_INSPECTION'::text, 'ACCEPTED'::text, 'REJECTED'::text, 'CANCELLED'::text])))
);

ALTER TABLE ONLY dispatch_reversal_items FORCE ROW LEVEL SECURITY;

CREATE TABLE dispatch_reversals (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    reversal_number text NOT NULL,
    dispatch_id uuid NOT NULL,
    return_reference text NOT NULL,
    reversal_date date NOT NULL,
    reason text NOT NULL,
    status text DEFAULT 'RETURN_RECEIVED'::text NOT NULL,
    closed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_dispatch_reversals_status CHECK ((status = ANY (ARRAY['EXPECTED'::text, 'RETURN_RECEIVED'::text, 'PENDING_INSPECTION'::text, 'CLOSED'::text, 'CANCELLED'::text])))
);

ALTER TABLE ONLY dispatch_reversals FORCE ROW LEVEL SECURITY;

-- Records that goods physically left. A dispatch record alone only reserves stock for a customer, and can still be
-- cancelled. Once this row exists the dispatch cannot be cancelled, and only a customer return can bring goods back.
CREATE TABLE dispatch_shipments (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    dispatch_id uuid NOT NULL,
    shipped_on date NOT NULL,
    carrier_reference text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL
);

ALTER TABLE ONLY dispatch_shipments FORCE ROW LEVEL SECURITY;

CREATE TABLE finished_products (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    serial_number text NOT NULL,
    production_batch_id uuid NOT NULL,
    dispatch_item_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL
);

ALTER TABLE ONLY finished_products FORCE ROW LEVEL SECURITY;

CREATE TABLE material_correction_approvals (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    correction_id uuid NOT NULL,
    approver_id uuid NOT NULL,
    note text,
    impact_acknowledged boolean NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL
);

ALTER TABLE ONLY material_correction_approvals FORCE ROW LEVEL SECURITY;

CREATE TABLE material_correction_items (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    correction_id uuid NOT NULL,
    field_name text NOT NULL,
    original_value text,
    corrected_value text NOT NULL,
    original_label text,
    corrected_label text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_material_correction_items_field CHECK ((field_name = ANY (ARRAY['MATERIAL'::text, 'SUPPLIER'::text, 'SUPPLIER_BATCH'::text, 'QUANTITY'::text, 'SOURCE_LOT'::text])))
);

ALTER TABLE ONLY material_correction_items FORCE ROW LEVEL SECURITY;

CREATE TABLE material_corrections (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    correction_number text NOT NULL,
    target_type text NOT NULL,
    receipt_item_id uuid,
    batch_relationship_id uuid,
    production_batch_id uuid,
    status text DEFAULT 'DRAFT'::text NOT NULL,
    reason text NOT NULL,
    additional_approval boolean DEFAULT false NOT NULL,
    submitted_at timestamp with time zone,
    decision_note text,
    decided_at timestamp with time zone,
    applied_at timestamp with time zone,
    replacement_relationship_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    facility_id uuid,
    CONSTRAINT ck_material_corrections_status CHECK ((status = ANY (ARRAY['DRAFT'::text, 'PENDING_APPROVAL'::text, 'APPROVED'::text, 'APPLIED'::text, 'REJECTED'::text, 'CANCELLED'::text]))),
    CONSTRAINT ck_material_corrections_target CHECK ((((target_type = 'LOT'::text) AND (receipt_item_id IS NOT NULL) AND (batch_relationship_id IS NULL)) OR ((target_type = 'CONSUMPTION'::text) AND (batch_relationship_id IS NOT NULL) AND (production_batch_id IS NOT NULL)))),
    CONSTRAINT ck_material_corrections_type CHECK ((target_type = ANY (ARRAY['LOT'::text, 'CONSUMPTION'::text])))
);

ALTER TABLE ONLY material_corrections FORCE ROW LEVEL SECURITY;

-- A member with no rows here sees every facility, which is how every existing member stays working.
CREATE TABLE membership_facilities (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    membership_id uuid NOT NULL,
    facility_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY membership_facilities FORCE ROW LEVEL SECURITY;

CREATE TABLE production_batches (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    batch_number text NOT NULL,
    product_id uuid NOT NULL,
    facility_id uuid NOT NULL,
    status text DEFAULT 'IN_PROGRESS'::text NOT NULL,
    planned_quantity numeric(18,6) NOT NULL,
    produced_quantity numeric(18,6),
    used_quantity numeric(18,6) DEFAULT 0 NOT NULL,
    dispatched_quantity numeric(18,6) DEFAULT 0 NOT NULL,
    completed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_production_batches_balance CHECK (((used_quantity + dispatched_quantity) <= COALESCE(produced_quantity, (0)::numeric))),
    CONSTRAINT ck_production_batches_planned CHECK ((planned_quantity > (0)::numeric)),
    CONSTRAINT ck_production_batches_release CHECK (((status = 'RELEASED'::text) OR ((used_quantity = (0)::numeric) AND (dispatched_quantity = (0)::numeric)))),
    CONSTRAINT ck_production_batches_status CHECK ((status = ANY (ARRAY['IN_PROGRESS'::text, 'COMPLETED'::text, 'ON_HOLD'::text, 'RELEASED'::text, 'REJECTED'::text])))
);

ALTER TABLE ONLY production_batches FORCE ROW LEVEL SECURITY;

CREATE TABLE recall_acknowledgements (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    contact_record_id uuid NOT NULL,
    acknowledged_on date NOT NULL,
    acknowledged_by text NOT NULL,
    customer_response text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL
);

ALTER TABLE ONLY recall_acknowledgements FORCE ROW LEVEL SECURITY;

CREATE TABLE recall_batches (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    recall_id uuid NOT NULL,
    production_batch_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL
);

ALTER TABLE ONLY recall_batches FORCE ROW LEVEL SECURITY;

-- Attempts and acknowledgements are history, so a wrong one is corrected by adding another.
CREATE TABLE recall_contact_attempts (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    contact_record_id uuid NOT NULL,
    method text NOT NULL,
    contact_person text,
    contact_details text,
    attempted_at timestamp with time zone NOT NULL,
    outcome text NOT NULL,
    customer_response text,
    notes text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_recall_attempts_method CHECK ((method = ANY (ARRAY['EMAIL'::text, 'PHONE'::text, 'WHATSAPP'::text, 'OTHER'::text]))),
    CONSTRAINT ck_recall_attempts_outcome CHECK ((outcome = ANY (ARRAY['NO_REPLY'::text, 'REACHED'::text, 'FAILED'::text])))
);

ALTER TABLE ONLY recall_contact_attempts FORCE ROW LEVEL SECURITY;

CREATE TABLE recall_contact_records (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    recall_id uuid NOT NULL,
    customer_id uuid NOT NULL,
    status text DEFAULT 'PENDING'::text NOT NULL,
    contact_person text,
    contact_details text,
    assigned_to uuid,
    follow_up_on date,
    notes text,
    exception_reason text,
    exception_by uuid,
    exception_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_recall_contacts_status CHECK ((status = ANY (ARRAY['PENDING'::text, 'CONTACT_ATTEMPTED'::text, 'CONTACTED'::text, 'ACKNOWLEDGED'::text, 'ACTION_REQUIRED'::text, 'RETURN_IN_PROGRESS'::text, 'RESOLVED'::text, 'UNREACHABLE'::text])))
);

ALTER TABLE ONLY recall_contact_records FORCE ROW LEVEL SECURITY;

CREATE TABLE recalls (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    recall_number text NOT NULL,
    recall_date date NOT NULL,
    reason text NOT NULL,
    instructions text NOT NULL,
    capa_id uuid,
    status text DEFAULT 'OPEN'::text NOT NULL,
    closure_requested_by uuid,
    closure_requested_at timestamp with time zone,
    closure_note text,
    closed_by uuid,
    closed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_recalls_closed CHECK (((status = 'CLOSED'::text) = (closed_at IS NOT NULL))),
    CONSTRAINT ck_recalls_status CHECK ((status = ANY (ARRAY['OPEN'::text, 'PENDING_CLOSURE'::text, 'CLOSED'::text]))),
    CONSTRAINT ck_recalls_two_people CHECK (((closed_by IS NULL) OR (closed_by <> closure_requested_by)))
);

ALTER TABLE ONLY recalls FORCE ROW LEVEL SECURITY;

CREATE TABLE trace_corrections (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    batch_relationship_id uuid,
    dispatch_id uuid,
    reason text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_trace_corrections_one_subject CHECK ((num_nonnulls(batch_relationship_id, dispatch_id) = 1))
);

ALTER TABLE ONLY trace_corrections FORCE ROW LEVEL SECURITY;

ALTER TABLE ONLY batch_relationships
    ADD CONSTRAINT batch_relationships_pkey PRIMARY KEY (id);

ALTER TABLE ONLY customers
    ADD CONSTRAINT customers_pkey PRIMARY KEY (id);

ALTER TABLE ONLY dispatch_items
    ADD CONSTRAINT dispatch_items_pkey PRIMARY KEY (id);

ALTER TABLE ONLY dispatch_records
    ADD CONSTRAINT dispatch_records_pkey PRIMARY KEY (id);

ALTER TABLE ONLY dispatch_reversal_items
    ADD CONSTRAINT dispatch_reversal_items_pkey PRIMARY KEY (id);

ALTER TABLE ONLY dispatch_reversals
    ADD CONSTRAINT dispatch_reversals_pkey PRIMARY KEY (id);

ALTER TABLE ONLY dispatch_shipments
    ADD CONSTRAINT dispatch_shipments_pkey PRIMARY KEY (id);

ALTER TABLE ONLY finished_products
    ADD CONSTRAINT finished_products_pkey PRIMARY KEY (id);

ALTER TABLE ONLY material_correction_approvals
    ADD CONSTRAINT material_correction_approvals_pkey PRIMARY KEY (id);

ALTER TABLE ONLY material_correction_items
    ADD CONSTRAINT material_correction_items_pkey PRIMARY KEY (id);

ALTER TABLE ONLY material_corrections
    ADD CONSTRAINT material_corrections_pkey PRIMARY KEY (id);

ALTER TABLE ONLY membership_facilities
    ADD CONSTRAINT membership_facilities_pkey PRIMARY KEY (id);

ALTER TABLE ONLY production_batches
    ADD CONSTRAINT production_batches_pkey PRIMARY KEY (id);

ALTER TABLE ONLY recall_acknowledgements
    ADD CONSTRAINT recall_acknowledgements_pkey PRIMARY KEY (id);

ALTER TABLE ONLY recall_batches
    ADD CONSTRAINT recall_batches_pkey PRIMARY KEY (id);

ALTER TABLE ONLY recall_contact_attempts
    ADD CONSTRAINT recall_contact_attempts_pkey PRIMARY KEY (id);

ALTER TABLE ONLY recall_contact_records
    ADD CONSTRAINT recall_contact_records_pkey PRIMARY KEY (id);

ALTER TABLE ONLY recalls
    ADD CONSTRAINT recalls_pkey PRIMARY KEY (id);

ALTER TABLE ONLY trace_corrections
    ADD CONSTRAINT trace_corrections_pkey PRIMARY KEY (id);

ALTER TABLE ONLY batch_relationships
    ADD CONSTRAINT uq_batch_relationships_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY customers
    ADD CONSTRAINT uq_customers_code UNIQUE (tenant_id, code);

ALTER TABLE ONLY customers
    ADD CONSTRAINT uq_customers_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY dispatch_items
    ADD CONSTRAINT uq_dispatch_items_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY dispatch_records
    ADD CONSTRAINT uq_dispatch_records_number UNIQUE (tenant_id, dispatch_number);

ALTER TABLE ONLY dispatch_records
    ADD CONSTRAINT uq_dispatch_records_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY dispatch_reversal_items
    ADD CONSTRAINT uq_dispatch_reversal_items_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY dispatch_reversals
    ADD CONSTRAINT uq_dispatch_reversals_number UNIQUE (tenant_id, reversal_number);

ALTER TABLE ONLY dispatch_reversals
    ADD CONSTRAINT uq_dispatch_reversals_reference UNIQUE (tenant_id, dispatch_id, return_reference);

ALTER TABLE ONLY dispatch_reversals
    ADD CONSTRAINT uq_dispatch_reversals_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY dispatch_shipments
    ADD CONSTRAINT uq_dispatch_shipments_dispatch UNIQUE (tenant_id, dispatch_id);

ALTER TABLE ONLY dispatch_shipments
    ADD CONSTRAINT uq_dispatch_shipments_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY finished_products
    ADD CONSTRAINT uq_finished_products_serial UNIQUE (tenant_id, serial_number);

ALTER TABLE ONLY finished_products
    ADD CONSTRAINT uq_finished_products_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY material_correction_approvals
    ADD CONSTRAINT uq_material_correction_approvals UNIQUE (tenant_id, correction_id, approver_id);

ALTER TABLE ONLY material_correction_items
    ADD CONSTRAINT uq_material_correction_items_field UNIQUE (tenant_id, correction_id, field_name);

ALTER TABLE ONLY material_corrections
    ADD CONSTRAINT uq_material_corrections_number UNIQUE (tenant_id, correction_number);

ALTER TABLE ONLY material_corrections
    ADD CONSTRAINT uq_material_corrections_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY membership_facilities
    ADD CONSTRAINT uq_membership_facilities UNIQUE (tenant_id, membership_id, facility_id);

ALTER TABLE ONLY production_batches
    ADD CONSTRAINT uq_production_batches_number UNIQUE (tenant_id, batch_number);

ALTER TABLE ONLY production_batches
    ADD CONSTRAINT uq_production_batches_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY recall_batches
    ADD CONSTRAINT uq_recall_batches UNIQUE (tenant_id, recall_id, production_batch_id);

ALTER TABLE ONLY recall_contact_records
    ADD CONSTRAINT uq_recall_contacts_customer UNIQUE (tenant_id, recall_id, customer_id);

ALTER TABLE ONLY recall_contact_records
    ADD CONSTRAINT uq_recall_contacts_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY recalls
    ADD CONSTRAINT uq_recalls_number UNIQUE (tenant_id, recall_number);

ALTER TABLE ONLY recalls
    ADD CONSTRAINT uq_recalls_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY trace_corrections
    ADD CONSTRAINT uq_trace_corrections_dispatch UNIQUE (tenant_id, dispatch_id);

ALTER TABLE ONLY trace_corrections
    ADD CONSTRAINT uq_trace_corrections_relationship UNIQUE (tenant_id, batch_relationship_id);

CREATE INDEX idx_corrections_facility ON material_corrections USING btree (tenant_id, facility_id);

CREATE INDEX ix_batch_rel_child ON batch_relationships USING btree (tenant_id, child_batch_id);

CREATE INDEX ix_batch_rel_lot ON batch_relationships USING btree (tenant_id, parent_lot_id);

CREATE INDEX ix_batch_rel_parent ON batch_relationships USING btree (tenant_id, parent_batch_id);

CREATE INDEX ix_customers_name_trgm ON customers USING gin (name gin_trgm_ops);

CREATE INDEX ix_dispatch_items_batch ON dispatch_items USING btree (tenant_id, production_batch_id);

CREATE INDEX ix_dispatch_records_number_trgm ON dispatch_records USING gin (dispatch_number gin_trgm_ops);

CREATE INDEX ix_dispatch_reversal_items_item ON dispatch_reversal_items USING btree (tenant_id, dispatch_item_id);

CREATE INDEX ix_dispatch_reversals_dispatch ON dispatch_reversals USING btree (tenant_id, dispatch_id);

CREATE INDEX ix_finished_products_batch ON finished_products USING btree (tenant_id, production_batch_id);

CREATE INDEX ix_finished_products_dispatch ON finished_products USING btree (tenant_id, dispatch_item_id);

CREATE INDEX ix_finished_products_serial_trgm ON finished_products USING gin (serial_number gin_trgm_ops);

CREATE INDEX ix_material_corrections_batch ON material_corrections USING btree (tenant_id, production_batch_id);

CREATE INDEX ix_material_corrections_lot ON material_corrections USING btree (tenant_id, receipt_item_id);

CREATE INDEX ix_production_batches_facility ON production_batches USING btree (tenant_id, facility_id);

CREATE INDEX ix_production_batches_number_trgm ON production_batches USING gin (batch_number gin_trgm_ops);

CREATE INDEX ix_production_batches_product ON production_batches USING btree (tenant_id, product_id);

CREATE INDEX ix_recall_acks_record ON recall_acknowledgements USING btree (tenant_id, contact_record_id);

CREATE INDEX ix_recall_attempts_record ON recall_contact_attempts USING btree (tenant_id, contact_record_id, attempted_at);

CREATE INDEX ix_recall_contacts_followup ON recall_contact_records USING btree (tenant_id, recall_id, follow_up_on);

CREATE INDEX ix_reversal_items_serials ON dispatch_reversal_items USING gin (serial_numbers jsonb_path_ops);

CREATE UNIQUE INDEX uq_material_corrections_open_consumption ON material_corrections USING btree (tenant_id, batch_relationship_id) WHERE ((target_type = 'CONSUMPTION'::text) AND (status = ANY (ARRAY['DRAFT'::text, 'PENDING_APPROVAL'::text, 'APPROVED'::text])));

CREATE UNIQUE INDEX uq_material_corrections_open_lot ON material_corrections USING btree (tenant_id, receipt_item_id) WHERE ((target_type = 'LOT'::text) AND (status = ANY (ARRAY['DRAFT'::text, 'PENDING_APPROVAL'::text, 'APPROVED'::text])));

CREATE TRIGGER trg_append_only BEFORE DELETE OR UPDATE ON batch_relationships FOR EACH ROW EXECUTE FUNCTION reject_change();

CREATE TRIGGER trg_append_only BEFORE DELETE OR UPDATE ON dispatch_items FOR EACH ROW EXECUTE FUNCTION reject_change();

CREATE TRIGGER trg_append_only BEFORE DELETE OR UPDATE ON dispatch_records FOR EACH ROW EXECUTE FUNCTION reject_change();

CREATE TRIGGER trg_append_only BEFORE DELETE OR UPDATE ON dispatch_shipments FOR EACH ROW EXECUTE FUNCTION reject_change();

CREATE TRIGGER trg_append_only BEFORE DELETE OR UPDATE ON material_correction_approvals FOR EACH ROW EXECUTE FUNCTION reject_change();

CREATE TRIGGER trg_append_only BEFORE DELETE OR UPDATE ON material_correction_items FOR EACH ROW EXECUTE FUNCTION reject_change();

CREATE TRIGGER trg_append_only BEFORE DELETE OR UPDATE ON trace_corrections FOR EACH ROW EXECUTE FUNCTION reject_change();

CREATE TRIGGER trg_no_delete BEFORE DELETE ON dispatch_reversal_items FOR EACH ROW EXECUTE FUNCTION protect_dispatch_return();

CREATE TRIGGER trg_no_delete BEFORE DELETE ON dispatch_reversals FOR EACH ROW EXECUTE FUNCTION protect_dispatch_return();

CREATE TRIGGER trg_protect_closed BEFORE UPDATE ON dispatch_reversals FOR EACH ROW EXECUTE FUNCTION protect_closed_reversal();

CREATE TRIGGER trg_protect_finished BEFORE DELETE OR UPDATE ON material_corrections FOR EACH ROW EXECUTE FUNCTION protect_finished_correction();

CREATE TRIGGER trg_protect_goods BEFORE UPDATE ON dispatch_reversal_items FOR EACH ROW EXECUTE FUNCTION protect_returned_goods();

CREATE TRIGGER trg_recall_acks_immutable BEFORE DELETE OR UPDATE ON recall_acknowledgements FOR EACH ROW EXECUTE FUNCTION reject_change();

CREATE TRIGGER trg_recall_attempts_immutable BEFORE DELETE OR UPDATE ON recall_contact_attempts FOR EACH ROW EXECUTE FUNCTION reject_change();

CREATE TRIGGER trg_recall_batches_immutable BEFORE DELETE OR UPDATE ON recall_batches FOR EACH ROW EXECUTE FUNCTION reject_change();

CREATE TRIGGER trg_recall_contacts_closed BEFORE DELETE OR UPDATE ON recall_contact_records FOR EACH ROW EXECUTE FUNCTION protect_closed_recall_contact();

CREATE TRIGGER trg_recalls_closed BEFORE DELETE OR UPDATE ON recalls FOR EACH ROW WHEN ((old.status = 'CLOSED'::text)) EXECUTE FUNCTION reject_change();

CREATE TRIGGER trg_require_live_dispatch BEFORE INSERT ON dispatch_shipments FOR EACH ROW EXECUTE FUNCTION require_live_dispatch_for_shipment();

CREATE TRIGGER trg_require_shipment BEFORE INSERT ON dispatch_reversals FOR EACH ROW EXECUTE FUNCTION require_shipment_for_return();

CREATE TRIGGER trg_require_unshipped BEFORE INSERT ON trace_corrections FOR EACH ROW EXECUTE FUNCTION require_unshipped_for_cancellation();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON batch_relationships FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON customers FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON dispatch_items FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON dispatch_records FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON dispatch_reversal_items FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON dispatch_reversals FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON dispatch_shipments FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON finished_products FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON material_correction_approvals FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON material_correction_items FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON material_corrections FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON membership_facilities FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON production_batches FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON recall_acknowledgements FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON recall_batches FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON recall_contact_attempts FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON recall_contact_records FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON recalls FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON trace_corrections FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE ONLY batch_relationships
    ADD CONSTRAINT batch_relationships_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY customers
    ADD CONSTRAINT customers_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY dispatch_items
    ADD CONSTRAINT dispatch_items_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY dispatch_records
    ADD CONSTRAINT dispatch_records_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY dispatch_reversal_items
    ADD CONSTRAINT dispatch_reversal_items_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY dispatch_reversals
    ADD CONSTRAINT dispatch_reversals_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY dispatch_shipments
    ADD CONSTRAINT dispatch_shipments_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY finished_products
    ADD CONSTRAINT finished_products_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY batch_relationships
    ADD CONSTRAINT fk_batch_rel_child FOREIGN KEY (tenant_id, child_batch_id) REFERENCES production_batches(tenant_id, id);

ALTER TABLE ONLY batch_relationships
    ADD CONSTRAINT fk_batch_rel_lot FOREIGN KEY (tenant_id, parent_lot_id) REFERENCES material_receipt_items(tenant_id, id);

ALTER TABLE ONLY batch_relationships
    ADD CONSTRAINT fk_batch_rel_parent FOREIGN KEY (tenant_id, parent_batch_id) REFERENCES production_batches(tenant_id, id);

ALTER TABLE ONLY material_corrections
    ADD CONSTRAINT fk_corrections_facility FOREIGN KEY (tenant_id, facility_id) REFERENCES facilities(tenant_id, id);

ALTER TABLE ONLY dispatch_records
    ADD CONSTRAINT fk_dispatch_customer FOREIGN KEY (tenant_id, customer_id) REFERENCES customers(tenant_id, id);

ALTER TABLE ONLY dispatch_items
    ADD CONSTRAINT fk_dispatch_items_batch FOREIGN KEY (tenant_id, production_batch_id) REFERENCES production_batches(tenant_id, id);

ALTER TABLE ONLY dispatch_items
    ADD CONSTRAINT fk_dispatch_items_dispatch FOREIGN KEY (tenant_id, dispatch_id) REFERENCES dispatch_records(tenant_id, id);

ALTER TABLE ONLY dispatch_reversal_items
    ADD CONSTRAINT fk_dispatch_reversal_items_batch FOREIGN KEY (tenant_id, production_batch_id) REFERENCES production_batches(tenant_id, id);

ALTER TABLE ONLY dispatch_reversal_items
    ADD CONSTRAINT fk_dispatch_reversal_items_item FOREIGN KEY (tenant_id, dispatch_item_id) REFERENCES dispatch_items(tenant_id, id);

ALTER TABLE ONLY dispatch_reversal_items
    ADD CONSTRAINT fk_dispatch_reversal_items_reversal FOREIGN KEY (tenant_id, reversal_id) REFERENCES dispatch_reversals(tenant_id, id);

ALTER TABLE ONLY dispatch_reversals
    ADD CONSTRAINT fk_dispatch_reversals_dispatch FOREIGN KEY (tenant_id, dispatch_id) REFERENCES dispatch_records(tenant_id, id);

ALTER TABLE ONLY dispatch_shipments
    ADD CONSTRAINT fk_dispatch_shipments_dispatch FOREIGN KEY (tenant_id, dispatch_id) REFERENCES dispatch_records(tenant_id, id);

ALTER TABLE ONLY finished_products
    ADD CONSTRAINT fk_finished_batch FOREIGN KEY (tenant_id, production_batch_id) REFERENCES production_batches(tenant_id, id);

ALTER TABLE ONLY finished_products
    ADD CONSTRAINT fk_finished_dispatch FOREIGN KEY (tenant_id, dispatch_item_id) REFERENCES dispatch_items(tenant_id, id);

ALTER TABLE ONLY inspections
    ADD CONSTRAINT fk_inspections_production_batch FOREIGN KEY (tenant_id, production_batch_id) REFERENCES production_batches(tenant_id, id);

ALTER TABLE ONLY inspections
    ADD CONSTRAINT fk_inspections_return_item FOREIGN KEY (tenant_id, return_item_id) REFERENCES dispatch_reversal_items(tenant_id, id);

ALTER TABLE ONLY material_correction_approvals
    ADD CONSTRAINT fk_material_correction_approvals_correction FOREIGN KEY (tenant_id, correction_id) REFERENCES material_corrections(tenant_id, id);

ALTER TABLE ONLY material_correction_items
    ADD CONSTRAINT fk_material_correction_items_correction FOREIGN KEY (tenant_id, correction_id) REFERENCES material_corrections(tenant_id, id);

ALTER TABLE ONLY material_corrections
    ADD CONSTRAINT fk_material_corrections_batch FOREIGN KEY (tenant_id, production_batch_id) REFERENCES production_batches(tenant_id, id);

ALTER TABLE ONLY material_corrections
    ADD CONSTRAINT fk_material_corrections_lot FOREIGN KEY (tenant_id, receipt_item_id) REFERENCES material_receipt_items(tenant_id, id);

ALTER TABLE ONLY material_corrections
    ADD CONSTRAINT fk_material_corrections_relationship FOREIGN KEY (tenant_id, batch_relationship_id) REFERENCES batch_relationships(tenant_id, id);

ALTER TABLE ONLY material_corrections
    ADD CONSTRAINT fk_material_corrections_replacement FOREIGN KEY (tenant_id, replacement_relationship_id) REFERENCES batch_relationships(tenant_id, id);

ALTER TABLE ONLY membership_facilities
    ADD CONSTRAINT fk_membership_facilities_facility FOREIGN KEY (tenant_id, facility_id) REFERENCES facilities(tenant_id, id);

ALTER TABLE ONLY membership_facilities
    ADD CONSTRAINT fk_membership_facilities_member FOREIGN KEY (tenant_id, membership_id) REFERENCES tenant_memberships(tenant_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY production_batches
    ADD CONSTRAINT fk_production_batches_facility FOREIGN KEY (tenant_id, facility_id) REFERENCES facilities(tenant_id, id);

ALTER TABLE ONLY production_batches
    ADD CONSTRAINT fk_production_batches_product FOREIGN KEY (tenant_id, product_id) REFERENCES products(tenant_id, id);

ALTER TABLE ONLY recall_acknowledgements
    ADD CONSTRAINT fk_recall_acks_record FOREIGN KEY (tenant_id, contact_record_id) REFERENCES recall_contact_records(tenant_id, id);

ALTER TABLE ONLY recall_contact_attempts
    ADD CONSTRAINT fk_recall_attempts_record FOREIGN KEY (tenant_id, contact_record_id) REFERENCES recall_contact_records(tenant_id, id);

ALTER TABLE ONLY recall_batches
    ADD CONSTRAINT fk_recall_batches_batch FOREIGN KEY (tenant_id, production_batch_id) REFERENCES production_batches(tenant_id, id);

ALTER TABLE ONLY recall_batches
    ADD CONSTRAINT fk_recall_batches_recall FOREIGN KEY (tenant_id, recall_id) REFERENCES recalls(tenant_id, id);

ALTER TABLE ONLY recall_contact_records
    ADD CONSTRAINT fk_recall_contacts_customer FOREIGN KEY (tenant_id, customer_id) REFERENCES customers(tenant_id, id);

ALTER TABLE ONLY recall_contact_records
    ADD CONSTRAINT fk_recall_contacts_recall FOREIGN KEY (tenant_id, recall_id) REFERENCES recalls(tenant_id, id);

ALTER TABLE ONLY recalls
    ADD CONSTRAINT fk_recalls_capa FOREIGN KEY (tenant_id, capa_id) REFERENCES corrective_actions(tenant_id, id);

ALTER TABLE ONLY trace_corrections
    ADD CONSTRAINT fk_trace_corrections_dispatch FOREIGN KEY (tenant_id, dispatch_id) REFERENCES dispatch_records(tenant_id, id);

ALTER TABLE ONLY trace_corrections
    ADD CONSTRAINT fk_trace_corrections_relationship FOREIGN KEY (tenant_id, batch_relationship_id) REFERENCES batch_relationships(tenant_id, id);

ALTER TABLE ONLY material_correction_approvals
    ADD CONSTRAINT material_correction_approvals_approver_id_fkey FOREIGN KEY (approver_id) REFERENCES users(id);

ALTER TABLE ONLY material_correction_approvals
    ADD CONSTRAINT material_correction_approvals_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY material_correction_items
    ADD CONSTRAINT material_correction_items_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY material_corrections
    ADD CONSTRAINT material_corrections_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY membership_facilities
    ADD CONSTRAINT membership_facilities_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY production_batches
    ADD CONSTRAINT production_batches_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY recall_acknowledgements
    ADD CONSTRAINT recall_acknowledgements_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY recall_batches
    ADD CONSTRAINT recall_batches_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY recall_contact_attempts
    ADD CONSTRAINT recall_contact_attempts_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY recall_contact_records
    ADD CONSTRAINT recall_contact_records_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY recalls
    ADD CONSTRAINT recalls_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY trace_corrections
    ADD CONSTRAINT trace_corrections_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE batch_relationships ENABLE ROW LEVEL SECURITY;

ALTER TABLE customers ENABLE ROW LEVEL SECURITY;

ALTER TABLE dispatch_items ENABLE ROW LEVEL SECURITY;

ALTER TABLE dispatch_records ENABLE ROW LEVEL SECURITY;

ALTER TABLE dispatch_reversal_items ENABLE ROW LEVEL SECURITY;

ALTER TABLE dispatch_reversals ENABLE ROW LEVEL SECURITY;

ALTER TABLE dispatch_shipments ENABLE ROW LEVEL SECURITY;

ALTER TABLE finished_products ENABLE ROW LEVEL SECURITY;

ALTER TABLE material_correction_approvals ENABLE ROW LEVEL SECURITY;

ALTER TABLE material_correction_items ENABLE ROW LEVEL SECURITY;

ALTER TABLE material_corrections ENABLE ROW LEVEL SECURITY;

ALTER TABLE membership_facilities ENABLE ROW LEVEL SECURITY;

ALTER TABLE production_batches ENABLE ROW LEVEL SECURITY;

ALTER TABLE recall_acknowledgements ENABLE ROW LEVEL SECURITY;

ALTER TABLE recall_batches ENABLE ROW LEVEL SECURITY;

ALTER TABLE recall_contact_attempts ENABLE ROW LEVEL SECURITY;

ALTER TABLE recall_contact_records ENABLE ROW LEVEL SECURITY;

ALTER TABLE recalls ENABLE ROW LEVEL SECURITY;

CREATE POLICY shipment_scan ON dispatch_records FOR SELECT TO "${flyway:user}" USING (true);

CREATE POLICY shipment_scan ON dispatch_shipments FOR SELECT TO "${flyway:user}" USING (true);

CREATE POLICY shipment_scan ON trace_corrections FOR SELECT TO "${flyway:user}" USING (true);

CREATE POLICY tenant_isolation ON batch_relationships USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON customers USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON dispatch_items USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON dispatch_records USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON dispatch_reversal_items USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON dispatch_reversals USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON dispatch_shipments USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON finished_products USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON material_correction_approvals USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON material_correction_items USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON material_corrections USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON membership_facilities USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON production_batches USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON recall_acknowledgements USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON recall_batches USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON recall_contact_attempts USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON recall_contact_records USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON recalls USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON trace_corrections USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

ALTER TABLE trace_corrections ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON FUNCTION expire_unverified_accounts(cutoff timestamp with time zone) FROM PUBLIC;
GRANT ALL ON FUNCTION expire_unverified_accounts(cutoff timestamp with time zone) TO ${approle};

REVOKE ALL ON FUNCTION tenants_with_capas_due() FROM PUBLIC;
GRANT ALL ON FUNCTION tenants_with_capas_due() TO ${approle};

REVOKE ALL ON FUNCTION tenants_with_unconfirmed_dispatches() FROM PUBLIC;
GRANT ALL ON FUNCTION tenants_with_unconfirmed_dispatches() TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE batch_relationships TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE customers TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE dispatch_items TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE dispatch_records TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE dispatch_reversal_items TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE dispatch_reversals TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE dispatch_shipments TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE finished_products TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE material_correction_approvals TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE material_correction_items TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE material_corrections TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE membership_facilities TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE production_batches TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE recall_acknowledgements TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE recall_batches TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE recall_contact_attempts TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE recall_contact_records TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE recalls TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE trace_corrections TO ${approle};
