-- Late production is chased once a week until it is done: a work order past its planned end, and a plan line past its
-- due date with less made than planned.
ALTER TABLE work_orders ADD COLUMN overdue_notified_at timestamp with time zone;
ALTER TABLE production_plan_lines ADD COLUMN overdue_notified_at timestamp with time zone;

-- Organisations with production that may be late, for the reminder job to visit. A day of slack covers time zones.
CREATE FUNCTION tenants_with_production_due() RETURNS SETOF uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
    SELECT tenant_id FROM work_orders
    WHERE status IN ('APPROVED', 'RELEASED', 'IN_PROGRESS', 'ON_HOLD') AND planned_end < now()::date + 1
    UNION
    SELECT l.tenant_id FROM production_plan_lines l
    JOIN production_plans p ON p.tenant_id = l.tenant_id AND p.id = l.plan_id
    WHERE p.status = 'APPROVED' AND l.due_date < now()::date + 1;
$$;

REVOKE ALL ON FUNCTION tenants_with_production_due() FROM PUBLIC;
GRANT ALL ON FUNCTION tenants_with_production_due() TO ${approle};
