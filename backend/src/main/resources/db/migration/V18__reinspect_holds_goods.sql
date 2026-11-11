-- A failed calibration can send goods back for inspection. A lot may then have a new inspection after a completed one,
-- and a batch that had already given out stock can go on hold.

DROP INDEX uq_inspections_active_item;
CREATE UNIQUE INDEX uq_inspections_active_item ON inspections USING btree (tenant_id, receipt_item_id)
    WHERE (status <> ALL (ARRAY['CANCELLED'::text, 'COMPLETED'::text]));

-- Stock leaves only a released batch, which the application checks on every use and dispatch. A batch still being
-- made has given out nothing; once released, a later hold or rejection keeps what already left.
ALTER TABLE production_batches DROP CONSTRAINT ck_production_batches_release;
ALTER TABLE production_batches ADD CONSTRAINT ck_production_batches_release
    CHECK (status <> 'IN_PROGRESS' OR (used_quantity = 0 AND dispatched_quantity = 0));
