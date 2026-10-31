-- A return can be announced before the goods arrive, and called off if they never do. While it is EXPECTED,
-- reversal_date is the date it is expected, and it becomes the date received. Quantity on an expected return is held
-- against the dispatch, but nothing can be inspected until the goods are here.
ALTER TABLE dispatch_reversals DROP CONSTRAINT ck_dispatch_reversals_status;
ALTER TABLE dispatch_reversals ADD CONSTRAINT ck_dispatch_reversals_status CHECK (status IN
    ('EXPECTED', 'RETURN_RECEIVED', 'PENDING_INSPECTION', 'CLOSED', 'CANCELLED'));
ALTER TABLE dispatch_reversal_items DROP CONSTRAINT ck_dispatch_reversal_items_status;
ALTER TABLE dispatch_reversal_items ADD CONSTRAINT ck_dispatch_reversal_items_status CHECK (status IN
    ('EXPECTED', 'RETURN_RECEIVED', 'PENDING_INSPECTION', 'ACCEPTED', 'REJECTED', 'CANCELLED'));

CREATE OR REPLACE FUNCTION protect_closed_reversal() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF OLD.status IN ('CLOSED', 'CANCELLED') THEN
        RAISE EXCEPTION 'dispatch_reversals % is closed', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION protect_returned_goods() RETURNS trigger
    LANGUAGE plpgsql AS
$$
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

-- A CAPA gets one reminder as its date approaches.
ALTER TABLE corrective_actions ADD COLUMN due_reminded_at TIMESTAMPTZ;

-- Both of these only say which tenants have work waiting. The work itself runs under each tenant's own context.
DO $$
BEGIN
    EXECUTE format('CREATE POLICY shipment_scan ON dispatch_records FOR SELECT TO %I USING (true)', current_user);
    EXECUTE format('CREATE POLICY shipment_scan ON dispatch_shipments FOR SELECT TO %I USING (true)', current_user);
    EXECUTE format('CREATE POLICY shipment_scan ON trace_corrections FOR SELECT TO %I USING (true)', current_user);
END;
$$;

CREATE FUNCTION tenants_with_capas_due() RETURNS SETOF UUID
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS
$$
    SELECT DISTINCT tenant_id FROM corrective_actions
    WHERE status NOT IN ('CLOSED', 'CANCELLED') AND target_date < now()::date + 5;
$$;
REVOKE ALL ON FUNCTION tenants_with_capas_due() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenants_with_capas_due() TO ${approle};

CREATE FUNCTION tenants_with_unconfirmed_dispatches() RETURNS SETOF UUID
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS
$$
    SELECT DISTINCT d.tenant_id FROM dispatch_records d
    WHERE d.dispatched_on < now()::date - 2
      AND NOT EXISTS (SELECT 1 FROM dispatch_shipments s WHERE s.tenant_id = d.tenant_id AND s.dispatch_id = d.id)
      AND NOT EXISTS (SELECT 1 FROM trace_corrections c WHERE c.tenant_id = d.tenant_id AND c.dispatch_id = d.id);
$$;
REVOKE ALL ON FUNCTION tenants_with_unconfirmed_dispatches() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tenants_with_unconfirmed_dispatches() TO ${approle};
