-- A customer return recorded as a quality complaint raises an NCR, linked to the return.
ALTER TABLE non_conformances ADD COLUMN dispatch_reversal_id uuid REFERENCES dispatch_reversals (id);

ALTER TABLE non_conformances DROP CONSTRAINT ck_ncr_source;
ALTER TABLE non_conformances
    ADD CONSTRAINT ck_ncr_source
        CHECK (source IN ('INSPECTION', 'MANUAL', 'AUDIT', 'CALIBRATION', 'PRODUCTION', 'CUSTOMER_RETURN')),
    ADD CONSTRAINT ck_ncr_return_source CHECK ((source = 'CUSTOMER_RETURN') = (dispatch_reversal_id IS NOT NULL));

-- One NCR per return, so a repeat cannot raise a second.
CREATE UNIQUE INDEX uq_ncr_dispatch_reversal ON non_conformances (tenant_id, dispatch_reversal_id)
    WHERE dispatch_reversal_id IS NOT NULL;
