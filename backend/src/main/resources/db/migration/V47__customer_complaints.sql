-- A customer complaint with nothing sent back, such as a phone call, raises an NCR linked to the dispatch.
ALTER TABLE non_conformances ADD COLUMN dispatch_id uuid REFERENCES dispatch_records (id);

ALTER TABLE non_conformances DROP CONSTRAINT ck_ncr_source;
ALTER TABLE non_conformances
    ADD CONSTRAINT ck_ncr_source CHECK (source IN ('INSPECTION', 'MANUAL', 'AUDIT', 'CALIBRATION', 'PRODUCTION',
        'CUSTOMER_RETURN', 'CUSTOMER_COMPLAINT')),
    ADD CONSTRAINT ck_ncr_complaint_source CHECK ((source = 'CUSTOMER_COMPLAINT') = (dispatch_id IS NOT NULL));

CREATE INDEX ix_ncr_dispatch ON non_conformances (tenant_id, dispatch_id) WHERE dispatch_id IS NOT NULL;
