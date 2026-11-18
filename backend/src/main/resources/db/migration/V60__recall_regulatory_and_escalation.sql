-- What a regulator asks about a recall: how dangerous it is, who was told, under which reference and when.
ALTER TABLE recalls
    ADD COLUMN hazard_class text CONSTRAINT ck_recalls_hazard_class
        CHECK (hazard_class IN ('CLASS_I', 'CLASS_II', 'CLASS_III')),
    ADD COLUMN regulator text,
    ADD COLUMN regulator_reference text,
    ADD COLUMN regulator_notified_on date,
    -- Days after the recall date that customers have to acknowledge it before the recall's approvers are told.
    ADD COLUMN response_days integer DEFAULT 3 NOT NULL
        CONSTRAINT ck_recalls_response_days CHECK (response_days BETWEEN 1 AND 60);

-- When a customer's silence was escalated, so that it is escalated only once.
ALTER TABLE recall_contact_records ADD COLUMN escalated_at timestamp with time zone;

-- Organisations with an open recall a customer has not acknowledged in time, for the escalation job to visit.
CREATE FUNCTION tenants_with_unanswered_recalls() RETURNS SETOF uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
    SELECT DISTINCT c.tenant_id FROM recall_contact_records c
    JOIN recalls r ON r.tenant_id = c.tenant_id AND r.id = c.recall_id
    WHERE r.status = 'OPEN' AND c.escalated_at IS NULL AND c.exception_at IS NULL AND c.status <> 'RESOLVED'
      AND r.recall_date + r.response_days < now()::date + 1
      AND NOT EXISTS (SELECT 1 FROM recall_acknowledgements a
                      WHERE a.tenant_id = c.tenant_id AND a.contact_record_id = c.id);
$$;

REVOKE ALL ON FUNCTION tenants_with_unanswered_recalls() FROM PUBLIC;
GRANT ALL ON FUNCTION tenants_with_unanswered_recalls() TO ${approle};

-- The function runs as its owner, who may read these across organisations only to find the ones to visit.
CREATE POLICY unanswered_scan ON recall_contact_records FOR SELECT TO "${flyway:user}" USING (true);
CREATE POLICY unanswered_scan ON recalls FOR SELECT TO "${flyway:user}" USING (true);
CREATE POLICY unanswered_scan ON recall_acknowledgements FOR SELECT TO "${flyway:user}" USING (true);
