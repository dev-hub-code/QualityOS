-- An inspection started because a failed calibration put an earlier one in doubt names that earlier inspection.
ALTER TABLE inspections ADD COLUMN reinspection_of uuid,
    ADD CONSTRAINT fk_inspections_reinspection_of FOREIGN KEY (tenant_id, reinspection_of) REFERENCES inspections (tenant_id, id);
CREATE INDEX ix_inspections_reinspection_of ON inspections (tenant_id, reinspection_of) WHERE reinspection_of IS NOT NULL;

-- The instrument that took each measurement, so that a failed calibration names the measurements in doubt.
ALTER TABLE inspection_results ADD COLUMN instrument_id uuid,
    ADD CONSTRAINT fk_inspection_results_instrument FOREIGN KEY (tenant_id, instrument_id) REFERENCES instruments (tenant_id, id);
CREATE INDEX ix_inspection_results_instrument ON inspection_results (tenant_id, instrument_id) WHERE instrument_id IS NOT NULL;
