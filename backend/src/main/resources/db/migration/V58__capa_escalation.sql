-- A CAPA, or an action of one, this many days past its date is escalated once to whoever approves CAPAs. 0: never.
ALTER TABLE tenant_settings ADD COLUMN capa_escalation_days integer DEFAULT 7 NOT NULL
    CONSTRAINT ck_tenant_settings_capa_escalation_days CHECK (capa_escalation_days BETWEEN 0 AND 90);

-- When each was escalated, so that it is escalated only once.
ALTER TABLE corrective_actions ADD COLUMN escalated_at timestamp with time zone;
ALTER TABLE capa_actions ADD COLUMN escalated_at timestamp with time zone;
