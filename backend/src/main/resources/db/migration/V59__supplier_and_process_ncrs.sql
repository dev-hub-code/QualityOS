-- Two more sources an NCR can be raised from by hand. A supplier issue names its supplier. A process incident names
-- where it happened: a production line, a work centre or a machine, at least one.
ALTER TABLE non_conformances
    ADD COLUMN production_line_id uuid,
    ADD COLUMN work_center_id uuid,
    ADD COLUMN machine_id uuid,
    ADD CONSTRAINT fk_ncr_production_line FOREIGN KEY (tenant_id, production_line_id)
        REFERENCES production_lines (tenant_id, id),
    ADD CONSTRAINT fk_ncr_work_center FOREIGN KEY (tenant_id, work_center_id) REFERENCES work_centers (tenant_id, id),
    ADD CONSTRAINT fk_ncr_machine FOREIGN KEY (tenant_id, machine_id) REFERENCES machines (tenant_id, id);

ALTER TABLE non_conformances DROP CONSTRAINT ck_ncr_source;
ALTER TABLE non_conformances
    ADD CONSTRAINT ck_ncr_source CHECK (source IN ('INSPECTION', 'MANUAL', 'AUDIT', 'CALIBRATION', 'PRODUCTION',
        'CUSTOMER_RETURN', 'CUSTOMER_COMPLAINT', 'SUPPLIER', 'PROCESS')),
    ADD CONSTRAINT ck_ncr_supplier_source CHECK (source <> 'SUPPLIER' OR supplier_id IS NOT NULL),
    ADD CONSTRAINT ck_ncr_process_source CHECK ((source = 'PROCESS')
        = (production_line_id IS NOT NULL OR work_center_id IS NOT NULL OR machine_id IS NOT NULL));

CREATE INDEX ix_ncr_production_line ON non_conformances (tenant_id, production_line_id)
    WHERE production_line_id IS NOT NULL;
CREATE INDEX ix_ncr_work_center ON non_conformances (tenant_id, work_center_id) WHERE work_center_id IS NOT NULL;
CREATE INDEX ix_ncr_machine ON non_conformances (tenant_id, machine_id) WHERE machine_id IS NOT NULL;
