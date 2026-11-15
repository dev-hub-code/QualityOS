-- A stop's responsible department is now one of the facility's departments, chosen rather than typed. The name stays
-- on the record as it was when chosen, so a renamed or archived department leaves history readable.
ALTER TABLE downtime_records
    ADD COLUMN responsible_department_id uuid,
    ADD CONSTRAINT fk_downtime_records_department
        FOREIGN KEY (tenant_id, responsible_department_id) REFERENCES departments(tenant_id, id);

CREATE INDEX ix_downtime_records_department ON downtime_records (tenant_id, responsible_department_id)
    WHERE responsible_department_id IS NOT NULL;
