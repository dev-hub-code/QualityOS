-- A failed calibration raises an NCR when the instrument was used since its last good calibration, and keeps the
-- list of inspections in doubt, so that each gets a recorded decision even after the instrument passes again.

ALTER TABLE non_conformances
    ADD COLUMN calibration_record_id uuid,
    ADD COLUMN instrument_id uuid;

ALTER TABLE non_conformances DROP CONSTRAINT ck_ncr_source;
ALTER TABLE non_conformances
    ADD CONSTRAINT ck_ncr_source CHECK (source IN ('INSPECTION', 'MANUAL', 'AUDIT', 'CALIBRATION')),
    ADD CONSTRAINT ck_ncr_calibration_source
        CHECK ((source = 'CALIBRATION') = (calibration_record_id IS NOT NULL AND instrument_id IS NOT NULL)),
    ADD CONSTRAINT fk_ncr_calibration_record
        FOREIGN KEY (tenant_id, calibration_record_id) REFERENCES calibration_records(tenant_id, id),
    ADD CONSTRAINT fk_ncr_instrument FOREIGN KEY (tenant_id, instrument_id) REFERENCES instruments(tenant_id, id);

-- One NCR per failed calibration.
CREATE UNIQUE INDEX uq_ncr_calibration_record ON non_conformances (tenant_id, calibration_record_id)
    WHERE calibration_record_id IS NOT NULL;

CREATE TABLE calibration_impact_reviews (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    calibration_record_id uuid NOT NULL,
    instrument_id uuid NOT NULL,
    inspection_id uuid NOT NULL,
    inspection_number text NOT NULL,
    used_on date NOT NULL,
    -- RESULT_STANDS: the measurement is still trusted. REINSPECT: the goods must be inspected again.
    decision text,
    note text,
    decided_by uuid,
    decided_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT calibration_impact_reviews_pkey PRIMARY KEY (id),
    CONSTRAINT uq_calibration_impact_reviews UNIQUE (tenant_id, calibration_record_id, inspection_id),
    CONSTRAINT ck_impact_review_decision CHECK (decision IN ('RESULT_STANDS', 'REINSPECT')),
    CONSTRAINT ck_impact_review_decided CHECK (
        (decision IS NULL) = (note IS NULL)
        AND (decision IS NULL) = (decided_by IS NULL)
        AND (decision IS NULL) = (decided_at IS NULL)),
    CONSTRAINT calibration_impact_reviews_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_impact_reviews_calibration
        FOREIGN KEY (tenant_id, calibration_record_id) REFERENCES calibration_records(tenant_id, id),
    CONSTRAINT fk_impact_reviews_instrument FOREIGN KEY (tenant_id, instrument_id) REFERENCES instruments(tenant_id, id),
    CONSTRAINT fk_impact_reviews_inspection FOREIGN KEY (tenant_id, inspection_id) REFERENCES inspections(tenant_id, id)
);

CREATE INDEX ix_impact_reviews_instrument ON calibration_impact_reviews (tenant_id, instrument_id);

ALTER TABLE calibration_impact_reviews ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY calibration_impact_reviews FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON calibration_impact_reviews
    USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

-- A review is decided once. After that, and for deletion, the row is history.
CREATE FUNCTION protect_decided_impact_review() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.decision IS NOT NULL THEN
        RAISE EXCEPTION 'impact review % is history', OLD.id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_impact_reviews_decided BEFORE DELETE OR UPDATE ON calibration_impact_reviews
    FOR EACH ROW EXECUTE FUNCTION protect_decided_impact_review();
CREATE TRIGGER trg_updated_at BEFORE UPDATE ON calibration_impact_reviews
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
