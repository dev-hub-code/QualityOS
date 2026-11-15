-- An audit can repeat: closing it plans the next one that many months after its planned date.
ALTER TABLE audits ADD COLUMN repeat_every_months integer;
ALTER TABLE audits ADD CONSTRAINT ck_audits_repeat CHECK (repeat_every_months BETWEEN 1 AND 36);
ALTER TABLE audits ADD COLUMN next_audit_id uuid;
ALTER TABLE audits ADD CONSTRAINT fk_audits_next_audit_id FOREIGN KEY (tenant_id, next_audit_id) REFERENCES audits (tenant_id, id);

-- The lead auditor is reminded once, a week before a planned audit. Changing the date sends a new reminder.
ALTER TABLE audits ADD COLUMN reminder_sent_at timestamp with time zone;

-- Organisations with a planned audit in the coming week not yet reminded, for the reminder job to visit.
CREATE FUNCTION tenants_with_audits_due() RETURNS SETOF uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
    SELECT DISTINCT tenant_id FROM audits
    WHERE status = 'PLANNED' AND reminder_sent_at IS NULL AND planned_on < now()::date + 9;
$$;

REVOKE ALL ON FUNCTION tenants_with_audits_due() FROM PUBLIC;
GRANT ALL ON FUNCTION tenants_with_audits_due() TO ${approle};

-- The function runs as its owner, who may read audits across organisations only to find the ones to visit.
CREATE POLICY due_scan ON audits FOR SELECT TO "${flyway:user}" USING (true);
