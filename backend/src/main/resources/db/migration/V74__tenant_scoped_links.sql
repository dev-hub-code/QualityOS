-- Links between two organisations' records include tenant_id, so a row can never point at another organisation's.
-- These seven named the other row by id alone. Units of measure and roles are left as they are: their shared rows
-- have no tenant.

ALTER TABLE state_holidays ADD CONSTRAINT uq_state_holidays_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE support_access_grants ADD CONSTRAINT uq_support_access_grants_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE facility_holidays DROP CONSTRAINT fk_facility_holidays_facility;
ALTER TABLE facility_holidays ADD CONSTRAINT fk_facility_holidays_facility
    FOREIGN KEY (tenant_id, facility_id) REFERENCES facilities (tenant_id, id);

ALTER TABLE facility_working_holidays DROP CONSTRAINT fk_facility_working_holidays_facility;
ALTER TABLE facility_working_holidays ADD CONSTRAINT fk_facility_working_holidays_facility
    FOREIGN KEY (tenant_id, facility_id) REFERENCES facilities (tenant_id, id);
ALTER TABLE facility_working_holidays DROP CONSTRAINT fk_facility_working_holidays_holiday;
ALTER TABLE facility_working_holidays ADD CONSTRAINT fk_facility_working_holidays_holiday
    FOREIGN KEY (tenant_id, state_holiday_id) REFERENCES state_holidays (tenant_id, id) ON DELETE CASCADE;

ALTER TABLE aql_switching_states DROP CONSTRAINT fk_aql_switching_template;
ALTER TABLE aql_switching_states ADD CONSTRAINT fk_aql_switching_template
    FOREIGN KEY (tenant_id, template_id) REFERENCES inspection_templates (tenant_id, id);

ALTER TABLE non_conformances DROP CONSTRAINT non_conformances_dispatch_id_fkey;
ALTER TABLE non_conformances ADD CONSTRAINT fk_non_conformances_dispatch
    FOREIGN KEY (tenant_id, dispatch_id) REFERENCES dispatch_records (tenant_id, id);
ALTER TABLE non_conformances DROP CONSTRAINT non_conformances_dispatch_reversal_id_fkey;
ALTER TABLE non_conformances ADD CONSTRAINT fk_non_conformances_dispatch_reversal
    FOREIGN KEY (tenant_id, dispatch_reversal_id) REFERENCES dispatch_reversals (tenant_id, id);

ALTER TABLE support_access_events DROP CONSTRAINT fk_support_access_events_grant;
ALTER TABLE support_access_events ADD CONSTRAINT fk_support_access_events_grant
    FOREIGN KEY (tenant_id, grant_id) REFERENCES support_access_grants (tenant_id, id);
