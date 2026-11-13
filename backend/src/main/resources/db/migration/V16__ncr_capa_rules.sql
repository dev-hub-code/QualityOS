-- Which NCRs need a closed CAPA before verification, and which CAPAs must have their fix verified as effective.
-- Each names the least severe NCR the rule covers: MINOR covers every NCR, NEVER none.
-- The defaults keep the rule that applied before: critical and major NCRs.
ALTER TABLE tenant_settings
    ADD COLUMN capa_required_from text DEFAULT 'MAJOR' NOT NULL,
    ADD COLUMN effectiveness_required_from text DEFAULT 'MAJOR' NOT NULL,
    ADD CONSTRAINT ck_tenant_settings_capa_required_from
        CHECK (capa_required_from IN ('CRITICAL', 'MAJOR', 'MINOR', 'NEVER')),
    ADD CONSTRAINT ck_tenant_settings_effectiveness_required_from
        CHECK (effectiveness_required_from IN ('CRITICAL', 'MAJOR', 'MINOR', 'NEVER'));
