-- AQL sampling grows the special inspection levels and the switching rules of ISO 2859-1 / ANSI Z1.4: a series of
-- lots (one template, one supplier) moves between normal, tightened and reduced inspection as its lots are decided,
-- and is discontinued after five rejections on tightened inspection.
ALTER TABLE inspection_template_versions DROP CONSTRAINT ck_template_versions_sampling;
ALTER TABLE inspection_template_versions ADD CONSTRAINT ck_template_versions_sampling CHECK (
    (sampling_type = 'FIXED' AND sample_size > 0)
    OR (sampling_type = 'PERCENTAGE' AND sample_percent > 0 AND sample_percent <= 100)
    OR sampling_type = 'FULL'
    OR (sampling_type = 'AQL' AND aql > 0 AND inspection_level IN ('I', 'II', 'III', 'S1', 'S2', 'S3', 'S4')));

-- How many sampled units were nonconforming, for an inspection sampled by AQL.
ALTER TABLE inspections ADD COLUMN nonconforming_units integer CHECK (nonconforming_units >= 0);

CREATE TABLE aql_switching_states (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    template_id uuid NOT NULL,
    -- The supplier of an incoming series; production lots have none.
    supplier_id uuid,
    severity text DEFAULT 'NORMAL' NOT NULL,
    -- The last lots decided on normal inspection, oldest first: A accepted, R rejected. At most five.
    recent_results text DEFAULT '' NOT NULL,
    switching_score integer DEFAULT 0 NOT NULL,
    tightened_accepted_in_row integer DEFAULT 0 NOT NULL,
    tightened_rejected integer DEFAULT 0 NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT aql_switching_states_pkey PRIMARY KEY (id),
    CONSTRAINT fk_aql_switching_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_aql_switching_template FOREIGN KEY (template_id) REFERENCES inspection_templates (id),
    CONSTRAINT ck_aql_switching_severity CHECK (severity IN ('NORMAL', 'TIGHTENED', 'REDUCED', 'DISCONTINUED')),
    CONSTRAINT ck_aql_switching_recent CHECK (recent_results ~ '^[AR]{0,5}$'),
    CONSTRAINT ck_aql_switching_counts CHECK (switching_score >= 0 AND tightened_accepted_in_row >= 0
        AND tightened_rejected >= 0)
);
CREATE UNIQUE INDEX uq_aql_switching_series ON aql_switching_states
    (tenant_id, template_id, COALESCE(supplier_id, '00000000-0000-0000-0000-000000000000'::uuid));

ALTER TABLE aql_switching_states ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY aql_switching_states FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON aql_switching_states
    USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));
