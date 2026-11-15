-- Shop-floor measurements name the instrument that took them, and machines list the gauges kept at them, so that a
-- failed calibration reaches the operations it measured. See docs/MANUFACTURING.md.

ALTER TABLE operation_measurements
    ADD COLUMN instrument_id uuid,
    ADD CONSTRAINT fk_operation_measurements_instrument
        FOREIGN KEY (tenant_id, instrument_id) REFERENCES instruments(tenant_id, id);

-- A failed calibration looks up what the instrument measured since its last good calibration.
CREATE INDEX ix_operation_measurements_instrument ON operation_measurements (tenant_id, instrument_id, created_at)
    WHERE instrument_id IS NOT NULL;

-- The instruments kept at a machine, offered first when measuring on it.
CREATE TABLE machine_instruments (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    machine_id uuid NOT NULL,
    instrument_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT machine_instruments_pkey PRIMARY KEY (id),
    CONSTRAINT uq_machine_instruments_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_machine_instruments UNIQUE (tenant_id, machine_id, instrument_id),
    CONSTRAINT fk_machine_instruments_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_machine_instruments_machine FOREIGN KEY (tenant_id, machine_id) REFERENCES machines(tenant_id, id),
    CONSTRAINT fk_machine_instruments_instrument
        FOREIGN KEY (tenant_id, instrument_id) REFERENCES instruments(tenant_id, id)
);

CREATE INDEX ix_machine_instruments_instrument ON machine_instruments (tenant_id, instrument_id);

SELECT apply_tenant_standards('machine_instruments');
