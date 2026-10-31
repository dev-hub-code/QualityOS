-- The Control Center, third part: the feature catalogue, usage limits, and business analytics.

-- Blank means no limit, so nothing changes for anyone until a limit is set.
ALTER TABLE subscription_plans
    ADD COLUMN max_storage_mb INTEGER,
    ADD COLUMN max_monthly_inspections INTEGER,
    ADD COLUMN max_attachments INTEGER,
    ADD COLUMN feature_values JSONB NOT NULL DEFAULT '{}',
    ADD CONSTRAINT ck_plan_usage_limits CHECK (
        COALESCE(max_storage_mb, 1) > 0 AND COALESCE(max_monthly_inspections, 1) > 0 AND COALESCE(max_attachments, 1) > 0);

-- What a plan may include. Whether a feature is actually enforced is decided in PlanAccess, not here.
CREATE TABLE feature_definitions (
    id UUID PRIMARY KEY,
    code TEXT NOT NULL,
    name TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    value_type TEXT NOT NULL DEFAULT 'BOOLEAN',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    default_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_feature_code UNIQUE (code),
    CONSTRAINT ck_feature_value_type CHECK (value_type IN ('BOOLEAN', 'NUMBER', 'TEXT', 'STRUCTURED'))
);
CREATE TRIGGER trg_updated_at BEFORE UPDATE ON feature_definitions FOR EACH ROW EXECUTE FUNCTION set_updated_at();
GRANT SELECT, INSERT, UPDATE ON feature_definitions TO ${approle};

INSERT INTO feature_definitions (id, code, name, description) VALUES
    (gen_random_uuid(), 'NCR_CAPA', 'NCR and CAPA', 'Raising and working non-conformances and corrective actions'),
    (gen_random_uuid(), 'REPORTS', 'Dashboards and reports', 'Quality dashboards, analytics and report exports'),
    (gen_random_uuid(), 'ADVANCED', 'Traceability, recalls, calibration and audits',
     'Lot tracing, recalls, instrument calibration and quality audits');

-- One row per organisation per day. Only the job writes these, through the function below.
CREATE TABLE organisation_usage_snapshots (
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    taken_on DATE NOT NULL,
    users BIGINT NOT NULL,
    facilities BIGINT NOT NULL,
    storage_bytes BIGINT NOT NULL,
    inspections BIGINT NOT NULL,
    attachments BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, taken_on)
);
ALTER TABLE organisation_usage_snapshots ENABLE ROW LEVEL SECURITY;
ALTER TABLE organisation_usage_snapshots FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    scanned TEXT;
BEGIN
    FOREACH scanned IN ARRAY ARRAY['attachments', 'inspections', 'invitations', 'facilities'] LOOP
        EXECUTE format('CREATE POLICY platform_scan ON %I FOR SELECT TO %I USING (true)', scanned, current_user);
    END LOOP;
    EXECUTE format('CREATE POLICY usage_job ON organisation_usage_snapshots TO %I USING (true) WITH CHECK (true)',
                   current_user);
END;
$$;

-- The same counts PlanLimits enforces, for every organisation at once.
CREATE FUNCTION organisation_usage_now()
    RETURNS TABLE (tenant_id UUID, users BIGINT, facilities BIGINT, storage_bytes BIGINT, inspections BIGINT,
                   attachments BIGINT)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS
$$
    SELECT t.id,
           (SELECT count(*) FROM tenant_memberships m WHERE m.tenant_id = t.id AND m.status = 'ACTIVE')
           + (SELECT count(*) FROM invitations i
              WHERE i.tenant_id = t.id AND i.status = 'PENDING' AND i.expires_at > now()),
           (SELECT count(*) FROM facilities f WHERE f.tenant_id = t.id AND f.archived_at IS NULL),
           (SELECT COALESCE(sum(a.size_bytes), 0)::BIGINT FROM attachments a
            WHERE a.tenant_id = t.id AND a.deleted_at IS NULL),
           (SELECT count(*) FROM inspections n WHERE n.tenant_id = t.id
              AND n.created_at >= date_trunc('month', now() AT TIME ZONE t.timezone) AT TIME ZONE t.timezone),
           (SELECT count(*) FROM attachments a WHERE a.tenant_id = t.id AND a.deleted_at IS NULL)
    FROM tenants t;
$$;
REVOKE ALL ON FUNCTION organisation_usage_now() FROM PUBLIC;

-- Safe to run again on the same day: the day's row is replaced.
CREATE FUNCTION record_usage_snapshots() RETURNS INTEGER
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS
$$
DECLARE
    written INTEGER;
BEGIN
    INSERT INTO organisation_usage_snapshots
    SELECT u.tenant_id, (now() AT TIME ZONE 'Asia/Kolkata')::DATE, u.users, u.facilities, u.storage_bytes,
           u.inspections, u.attachments
    FROM organisation_usage_now() u
    JOIN subscriptions s ON s.tenant_id = u.tenant_id
    ON CONFLICT (tenant_id, taken_on) DO UPDATE SET
        users = excluded.users, facilities = excluded.facilities, storage_bytes = excluded.storage_bytes,
        inspections = excluded.inspections, attachments = excluded.attachments;
    GET DIAGNOSTICS written = ROW_COUNT;
    RETURN written;
END;
$$;
REVOKE ALL ON FUNCTION record_usage_snapshots() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION record_usage_snapshots() TO ${approle};

CREATE FUNCTION usage_share(p_used NUMERIC, p_limit NUMERIC) RETURNS NUMERIC
    LANGUAGE sql IMMUTABLE AS
$$
    SELECT CASE WHEN p_limit IS NULL THEN 0 ELSE p_used / p_limit END;
$$;

CREATE FUNCTION platform_usage_page(p_search TEXT, p_alert TEXT, p_limit INT, p_offset INT)
    RETURNS TABLE (tenant_id UUID, name TEXT, plan_id UUID, plan_name TEXT, display_order INT, features TEXT,
                   users BIGINT, max_users INT, facilities BIGINT, max_facilities INT, storage_bytes BIGINT,
                   max_storage_mb INT, inspections BIGINT, max_monthly_inspections INT, attachments BIGINT,
                   max_attachments INT, highest NUMERIC, total BIGINT)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS
$$
#variable_conflict use_column
DECLARE
    pattern TEXT := '%' || replace(replace(replace(COALESCE(p_search, ''), '\', '\\'), '%', '\%'), '_', '\_') || '%';
BEGIN
    PERFORM require_platform_admin();
    RETURN QUERY
    WITH measured AS (
        SELECT t.id, t.name, p.id AS plan_id, p.name AS plan_name, p.display_order,
               array_to_string(p.features, ',') AS features, u.users, p.max_users, u.facilities, p.max_facilities,
               u.storage_bytes, p.max_storage_mb, u.inspections, p.max_monthly_inspections, u.attachments,
               p.max_attachments,
               GREATEST(usage_share(u.users, p.max_users), usage_share(u.facilities, p.max_facilities),
                        usage_share(u.storage_bytes / 1048576.0, p.max_storage_mb),
                        usage_share(u.inspections, p.max_monthly_inspections),
                        usage_share(u.attachments, p.max_attachments)) AS highest
        FROM organisation_usage_now() u
        JOIN tenants t ON t.id = u.tenant_id
        JOIN subscriptions s ON s.tenant_id = t.id
        JOIN subscription_plans p ON p.id = s.plan_id
        WHERE p_search IS NULL OR t.name ILIKE pattern
    )
    SELECT m.id, m.name, m.plan_id, m.plan_name, m.display_order, m.features, m.users, m.max_users, m.facilities,
           m.max_facilities, m.storage_bytes, m.max_storage_mb, m.inspections, m.max_monthly_inspections,
           m.attachments, m.max_attachments, round(m.highest, 4), count(*) OVER ()
    FROM measured m
    WHERE p_alert IS NULL
       OR (p_alert = 'NEAR' AND m.highest >= 0.8 AND m.highest < 1)
       OR (p_alert = 'REACHED' AND m.highest >= 1)
    ORDER BY m.highest DESC, m.name, m.id
    LIMIT p_limit OFFSET p_offset;
END;
$$;
REVOKE ALL ON FUNCTION platform_usage_page(TEXT, TEXT, INT, INT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_usage_page(TEXT, TEXT, INT, INT) TO ${approle};

CREATE FUNCTION platform_usage_history(p_tenant UUID, p_days INT)
    RETURNS TABLE (taken_on DATE, users BIGINT, facilities BIGINT, storage_bytes BIGINT, inspections BIGINT,
                   attachments BIGINT)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS
$$
#variable_conflict use_column
BEGIN
    PERFORM require_platform_admin();
    RETURN QUERY
    SELECT h.taken_on, h.users, h.facilities, h.storage_bytes, h.inspections, h.attachments
    FROM organisation_usage_snapshots h
    WHERE h.tenant_id = p_tenant AND h.taken_on > current_date - p_days
    ORDER BY h.taken_on DESC;
END;
$$;
REVOKE ALL ON FUNCTION platform_usage_history(UUID, INT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_usage_history(UUID, INT) TO ${approle};

-- How many organisations have each feature, through their current plan.
CREATE FUNCTION platform_feature_counts() RETURNS TABLE (code TEXT, organisations BIGINT)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS
$$
#variable_conflict use_column
BEGIN
    PERFORM require_platform_admin();
    RETURN QUERY
    SELECT f.code, count(*)
    FROM subscriptions s
    JOIN subscription_plans p ON p.id = s.plan_id
    CROSS JOIN LATERAL unnest(p.features) AS f (code)
    GROUP BY f.code;
END;
$$;
REVOKE ALL ON FUNCTION platform_feature_counts() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_feature_counts() TO ${approle};

CREATE FUNCTION platform_feature_organisations(p_code TEXT, p_limit INT, p_offset INT)
    RETURNS TABLE (tenant_id UUID, name TEXT, plan_name TEXT, total BIGINT)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS
$$
#variable_conflict use_column
BEGIN
    PERFORM require_platform_admin();
    RETURN QUERY
    SELECT t.id, t.name, p.name, count(*) OVER ()
    FROM subscriptions s
    JOIN subscription_plans p ON p.id = s.plan_id
    JOIN tenants t ON t.id = s.tenant_id
    WHERE p_code = ANY (p.features)
    ORDER BY t.name, t.id
    LIMIT p_limit OFFSET p_offset;
END;
$$;
REVOKE ALL ON FUNCTION platform_feature_organisations(TEXT, INT, INT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_feature_organisations(TEXT, INT, INT) TO ${approle};

-- docs/PLATFORM_ADMIN.md defines every figure here. The dashboard's own figures are reused, so the two cannot disagree.
CREATE FUNCTION platform_analytics(p_start TIMESTAMPTZ, p_end TIMESTAMPTZ, p_zone TEXT) RETURNS JSONB
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public AS
$$
DECLARE
    closing TIMESTAMPTZ := LEAST(p_end, now());
    dashboard JSONB := platform_dashboard(p_start, p_end, p_zone);
    result JSONB;
BEGIN
    WITH opened AS (SELECT * FROM platform_contracts_at(p_start)),
         closed AS (SELECT * FROM platform_contracts_at(closing)),
         first_paid AS (
             SELECT i.tenant_id, min(i.paid_at) AS paid_at FROM subscription_invoices i
             WHERE i.status = 'PAID' GROUP BY i.tenant_id),
         cohort AS (SELECT t.id FROM tenants t WHERE t.created_at >= p_start AND t.created_at < p_end),
         lost AS (
             SELECT o.tenant_id, GREATEST(o.monthly - COALESCE(c.monthly, 0), 0) AS amount, c.tenant_id IS NULL AS gone
             FROM opened o LEFT JOIN closed c ON c.tenant_id = o.tenant_id)
    SELECT jsonb_build_object(
        'mrr', (SELECT COALESCE(round(sum(monthly), 2), 0) FROM closed),
        'arr', (SELECT COALESCE(round(sum(monthly) * 12, 2), 0) FROM closed),
        'openingMrr', (SELECT COALESCE(round(sum(monthly), 2), 0) FROM opened),
        'openingCustomers', (SELECT count(*) FROM opened),
        'closingCustomers', (SELECT count(*) FROM closed),
        'newCustomers', (SELECT count(*) FROM first_paid f WHERE f.paid_at >= p_start AND f.paid_at < p_end),
        'registered', (SELECT count(*) FROM cohort),
        'converted', (SELECT count(*) FROM cohort c JOIN first_paid f ON f.tenant_id = c.id),
        'trialConversion', (SELECT round(100.0 * count(f.tenant_id) / NULLIF(count(*), 0), 1)
                            FROM cohort c LEFT JOIN first_paid f ON f.tenant_id = c.id),
        'lostCustomers', (SELECT count(*) FROM lost WHERE gone),
        'customerChurn', (SELECT round(100.0 * count(*) FILTER (WHERE gone) / NULLIF(count(*), 0), 1) FROM lost),
        'revenueLost', (SELECT COALESCE(round(sum(amount), 2), 0) FROM lost),
        'revenueChurn', (SELECT round(100 * sum(amount) / NULLIF((SELECT sum(monthly) FROM opened), 0), 1) FROM lost),
        'averageRevenue', (SELECT round(sum(monthly) / NULLIF(count(*), 0), 2) FROM closed),
        'revenueByPlan', (SELECT COALESCE(jsonb_agg(jsonb_build_object('name', x.plan_name, 'mrr', x.mrr)
                                                    ORDER BY x.mrr DESC), '[]')
                          FROM (SELECT plan_name, round(sum(monthly), 2) AS mrr FROM closed GROUP BY plan_name) x)
    ) INTO result;
    RETURN result || jsonb_build_object(
        'outstanding', dashboard -> 'outstanding', 'collected', dashboard -> 'collected',
        'collectionRate', dashboard -> 'collectionRate', 'plans', dashboard -> 'plans',
        'months', dashboard -> 'months');
END;
$$;
REVOKE ALL ON FUNCTION platform_analytics(TIMESTAMPTZ, TIMESTAMPTZ, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_analytics(TIMESTAMPTZ, TIMESTAMPTZ, TEXT) TO ${approle};
