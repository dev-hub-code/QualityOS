-- Genealogy and dispatch stay append-only, so a mistake is corrected by a new row that reverses the original.
-- The original remains readable, and each one can be reversed once.
ALTER TABLE batch_relationships ADD CONSTRAINT uq_batch_relationships_tenant_id UNIQUE (tenant_id, id);

CREATE TABLE trace_corrections (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    batch_relationship_id UUID,
    dispatch_id UUID,
    reason TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_trace_corrections_relationship FOREIGN KEY (tenant_id, batch_relationship_id)
        REFERENCES batch_relationships (tenant_id, id),
    CONSTRAINT fk_trace_corrections_dispatch FOREIGN KEY (tenant_id, dispatch_id)
        REFERENCES dispatch_records (tenant_id, id),
    CONSTRAINT ck_trace_corrections_one_subject CHECK (num_nonnulls(batch_relationship_id, dispatch_id) = 1),
    CONSTRAINT uq_trace_corrections_relationship UNIQUE (tenant_id, batch_relationship_id),
    CONSTRAINT uq_trace_corrections_dispatch UNIQUE (tenant_id, dispatch_id)
);
SELECT apply_tenant_standards('trace_corrections');

CREATE TRIGGER trg_append_only BEFORE UPDATE OR DELETE ON trace_corrections
    FOR EACH ROW EXECUTE FUNCTION reject_change();
