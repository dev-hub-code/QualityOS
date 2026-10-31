-- Records that goods physically left. A dispatch record alone only reserves stock for a customer, and can still be
-- cancelled. Once this row exists the dispatch cannot be cancelled, and only a customer return can bring goods back.
CREATE TABLE dispatch_shipments (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    dispatch_id UUID NOT NULL,
    shipped_on DATE NOT NULL,
    carrier_reference TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_dispatch_shipments_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_dispatch_shipments_dispatch UNIQUE (tenant_id, dispatch_id),
    CONSTRAINT fk_dispatch_shipments_dispatch FOREIGN KEY (tenant_id, dispatch_id)
        REFERENCES dispatch_records (tenant_id, id)
);
SELECT apply_tenant_standards('dispatch_shipments');

-- There is no evidence either way for older dispatches. Treating them as shipped can only stop a cancellation, which
-- is the safe mistake: the other one would put goods a customer holds back into stock. Row-level security is lifted
-- so that the migration sees every tenant, and restored straight after.
ALTER TABLE dispatch_records NO FORCE ROW LEVEL SECURITY;
ALTER TABLE trace_corrections NO FORCE ROW LEVEL SECURITY;
ALTER TABLE dispatch_shipments NO FORCE ROW LEVEL SECURITY;
INSERT INTO dispatch_shipments (id, tenant_id, dispatch_id, shipped_on, carrier_reference)
SELECT gen_random_uuid(), d.tenant_id, d.id, d.dispatched_on, 'Recorded before shipments were tracked'
FROM dispatch_records d
WHERE NOT EXISTS (SELECT 1 FROM trace_corrections c WHERE c.tenant_id = d.tenant_id AND c.dispatch_id = d.id);
ALTER TABLE dispatch_records FORCE ROW LEVEL SECURITY;
ALTER TABLE trace_corrections FORCE ROW LEVEL SECURITY;
ALTER TABLE dispatch_shipments FORCE ROW LEVEL SECURITY;

CREATE TRIGGER trg_append_only BEFORE UPDATE OR DELETE ON dispatch_shipments
    FOR EACH ROW EXECUTE FUNCTION reject_change();

CREATE FUNCTION require_unshipped_for_cancellation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
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
CREATE TRIGGER trg_require_unshipped BEFORE INSERT ON trace_corrections
    FOR EACH ROW EXECUTE FUNCTION require_unshipped_for_cancellation();

CREATE FUNCTION require_live_dispatch_for_shipment() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF EXISTS (SELECT 1 FROM trace_corrections c WHERE c.tenant_id = NEW.tenant_id AND c.dispatch_id = NEW.dispatch_id) THEN
        RAISE EXCEPTION 'dispatch % was cancelled', NEW.dispatch_id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_require_live_dispatch BEFORE INSERT ON dispatch_shipments
    FOR EACH ROW EXECUTE FUNCTION require_live_dispatch_for_shipment();

CREATE FUNCTION require_shipment_for_return() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM dispatch_shipments s WHERE s.tenant_id = NEW.tenant_id AND s.dispatch_id = NEW.dispatch_id) THEN
        RAISE EXCEPTION 'dispatch % has not shipped', NEW.dispatch_id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_require_shipment BEFORE INSERT ON dispatch_reversals
    FOR EACH ROW EXECUTE FUNCTION require_shipment_for_return();
