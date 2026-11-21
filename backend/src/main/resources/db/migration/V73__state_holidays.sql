-- Holidays an organisation keeps for a whole state, or for all of India, which every facility there takes.
-- region: a state's two-digit GST code, such as 27 for Maharashtra, or ALL for every state.
CREATE TABLE state_holidays (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    region text NOT NULL,
    holiday_on date NOT NULL,
    name text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    CONSTRAINT state_holidays_pkey PRIMARY KEY (id),
    CONSTRAINT fk_state_holidays_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT ck_state_holidays_region CHECK (region = 'ALL' OR region ~ '^[0-9]{2}$'),
    CONSTRAINT ck_state_holidays_name CHECK (length(btrim(name)) BETWEEN 1 AND 200)
);
CREATE UNIQUE INDEX uq_state_holidays_day ON state_holidays (tenant_id, region, holiday_on);

ALTER TABLE state_holidays ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY state_holidays FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON state_holidays
    USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

-- A state holiday a facility works on all the same.
CREATE TABLE facility_working_holidays (
    tenant_id uuid NOT NULL,
    facility_id uuid NOT NULL,
    state_holiday_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    CONSTRAINT facility_working_holidays_pkey PRIMARY KEY (facility_id, state_holiday_id),
    CONSTRAINT fk_facility_working_holidays_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_facility_working_holidays_facility FOREIGN KEY (facility_id) REFERENCES facilities (id),
    CONSTRAINT fk_facility_working_holidays_holiday FOREIGN KEY (state_holiday_id) REFERENCES state_holidays (id)
        ON DELETE CASCADE
);
CREATE INDEX ix_facility_working_holidays_holiday ON facility_working_holidays (state_holiday_id);

ALTER TABLE facility_working_holidays ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY facility_working_holidays FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON facility_working_holidays
    USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));
