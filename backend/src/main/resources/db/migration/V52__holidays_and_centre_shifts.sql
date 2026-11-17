-- A facility's holidays have no working time, and a work centre can work only some of its facility's shifts.
CREATE TABLE facility_holidays (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    facility_id uuid NOT NULL,
    holiday_on date NOT NULL,
    name text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    CONSTRAINT facility_holidays_pkey PRIMARY KEY (id),
    CONSTRAINT fk_facility_holidays_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_facility_holidays_facility FOREIGN KEY (facility_id) REFERENCES facilities (id),
    CONSTRAINT ck_facility_holidays_name CHECK (length(btrim(name)) BETWEEN 1 AND 200)
);
CREATE UNIQUE INDEX uq_facility_holidays_day ON facility_holidays (tenant_id, facility_id, holiday_on);

ALTER TABLE facility_holidays ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY facility_holidays FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON facility_holidays
    USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

-- Empty: the centre works every shift of its facility.
ALTER TABLE work_centers ADD COLUMN shift_ids jsonb NOT NULL DEFAULT '[]'::jsonb;
