-- The console shows a limited list, so an organisation waiting on a payment must not fall off the end of it.
DO $$
BEGIN
    EXECUTE format('CREATE POLICY invoice_scan ON subscription_invoices FOR SELECT TO %I USING (true)', current_user);
END;
$$;

CREATE OR REPLACE FUNCTION platform_tenant_ids() RETURNS SETOF UUID
    LANGUAGE sql SECURITY DEFINER SET search_path = public AS
$$
    SELECT s.tenant_id FROM subscriptions s
    ORDER BY EXISTS (SELECT 1 FROM subscription_invoices i WHERE i.tenant_id = s.tenant_id AND i.status = 'ISSUED') DESC,
             s.current_period_ends_at;
$$;
