-- Each CAPA action past its target date is chased once a week until it is done, as the CAPA itself is.
ALTER TABLE capa_actions ADD COLUMN overdue_notified_at timestamp with time zone;

-- Organisations with a CAPA, or an open action of one, due soon or late, for the reminder job to visit.
CREATE OR REPLACE FUNCTION tenants_with_capas_due() RETURNS SETOF uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
    SELECT tenant_id FROM corrective_actions
    WHERE status NOT IN ('CLOSED', 'CANCELLED') AND target_date < now()::date + 5
    UNION
    SELECT a.tenant_id FROM capa_actions a
    JOIN corrective_actions c ON c.tenant_id = a.tenant_id AND c.id = a.capa_id
    WHERE c.status NOT IN ('CLOSED', 'CANCELLED') AND a.completed_at IS NULL AND a.target_date < now()::date + 1;
$$;

-- The function runs as its owner, who may read actions across organisations only to find the ones to visit.
CREATE POLICY overdue_scan ON capa_actions FOR SELECT TO "${flyway:user}" USING (true);
