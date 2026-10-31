-- Physical returns against a dispatch. The dispatch stays as it was. Returned goods wait in quarantine, and stock
-- comes back only when an inspection accepts them. Credit notes and refunds are not part of this.

CREATE TABLE dispatch_reversals (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    reversal_number TEXT NOT NULL,
    dispatch_id UUID NOT NULL,
    return_reference TEXT NOT NULL,
    reversal_date DATE NOT NULL,
    reason TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'RETURN_RECEIVED',
    closed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_dispatch_reversals_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_dispatch_reversals_number UNIQUE (tenant_id, reversal_number),
    -- The same return sent twice is refused rather than counted twice.
    CONSTRAINT uq_dispatch_reversals_reference UNIQUE (tenant_id, dispatch_id, return_reference),
    CONSTRAINT fk_dispatch_reversals_dispatch FOREIGN KEY (tenant_id, dispatch_id) REFERENCES dispatch_records (tenant_id, id),
    CONSTRAINT ck_dispatch_reversals_status CHECK (status IN ('RETURN_RECEIVED', 'PENDING_INSPECTION', 'CLOSED'))
);
CREATE INDEX ix_dispatch_reversals_dispatch ON dispatch_reversals (tenant_id, dispatch_id);
SELECT apply_tenant_standards('dispatch_reversals');

CREATE TABLE dispatch_reversal_items (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    reversal_id UUID NOT NULL,
    dispatch_item_id UUID NOT NULL,
    production_batch_id UUID NOT NULL,
    quantity NUMERIC(18, 6) NOT NULL,
    serial_numbers JSONB NOT NULL DEFAULT '[]',
    status TEXT NOT NULL DEFAULT 'RETURN_RECEIVED',
    disposition TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_dispatch_reversal_items_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_dispatch_reversal_items_reversal FOREIGN KEY (tenant_id, reversal_id) REFERENCES dispatch_reversals (tenant_id, id),
    CONSTRAINT fk_dispatch_reversal_items_item FOREIGN KEY (tenant_id, dispatch_item_id) REFERENCES dispatch_items (tenant_id, id),
    CONSTRAINT fk_dispatch_reversal_items_batch FOREIGN KEY (tenant_id, production_batch_id) REFERENCES production_batches (tenant_id, id),
    CONSTRAINT ck_dispatch_reversal_items_quantity CHECK (quantity > 0),
    CONSTRAINT ck_dispatch_reversal_items_status CHECK (status IN
        ('RETURN_RECEIVED', 'PENDING_INSPECTION', 'ACCEPTED', 'REJECTED'))
);
CREATE INDEX ix_dispatch_reversal_items_item ON dispatch_reversal_items (tenant_id, dispatch_item_id);
SELECT apply_tenant_standards('dispatch_reversal_items');

CREATE FUNCTION protect_dispatch_return() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION '% % cannot be deleted', TG_TABLE_NAME, OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE FUNCTION protect_closed_reversal() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF OLD.status = 'CLOSED' THEN
        RAISE EXCEPTION 'dispatch_reversals % is closed', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE FUNCTION protect_returned_goods() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF OLD.status IN ('ACCEPTED', 'REJECTED')
        OR NEW.quantity <> OLD.quantity
        OR NEW.dispatch_item_id <> OLD.dispatch_item_id
        OR NEW.serial_numbers <> OLD.serial_numbers THEN
        RAISE EXCEPTION 'dispatch_reversal_items % cannot be changed', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_no_delete BEFORE DELETE ON dispatch_reversals
    FOR EACH ROW EXECUTE FUNCTION protect_dispatch_return();
CREATE TRIGGER trg_no_delete BEFORE DELETE ON dispatch_reversal_items
    FOR EACH ROW EXECUTE FUNCTION protect_dispatch_return();
CREATE TRIGGER trg_protect_closed BEFORE UPDATE ON dispatch_reversals
    FOR EACH ROW EXECUTE FUNCTION protect_closed_reversal();
CREATE TRIGGER trg_protect_goods BEFORE UPDATE ON dispatch_reversal_items
    FOR EACH ROW EXECUTE FUNCTION protect_returned_goods();

-- A return inspection is an inspection of its production batch, tied to the goods that came back.
ALTER TABLE inspections ADD COLUMN return_item_id UUID;
ALTER TABLE inspections ADD CONSTRAINT fk_inspections_return_item
    FOREIGN KEY (tenant_id, return_item_id) REFERENCES dispatch_reversal_items (tenant_id, id);
ALTER TABLE inspections ADD CONSTRAINT ck_inspections_return_of_batch
    CHECK (return_item_id IS NULL OR production_batch_id IS NOT NULL);
DROP INDEX uq_inspections_active_batch;
CREATE UNIQUE INDEX uq_inspections_active_batch ON inspections (tenant_id, production_batch_id)
    WHERE production_batch_id IS NOT NULL AND return_item_id IS NULL AND status NOT IN ('CANCELLED', 'COMPLETED');
CREATE UNIQUE INDEX uq_inspections_active_return ON inspections (tenant_id, return_item_id)
    WHERE return_item_id IS NOT NULL AND status NOT IN ('CANCELLED', 'COMPLETED');
