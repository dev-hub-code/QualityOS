-- Online payment moves from Cashfree to Razorpay. Rows already written under CASHFREE are kept as history and are
-- never sent anywhere again: the refunds job now looks only at Razorpay.
ALTER TABLE refunds DROP CONSTRAINT ck_refunds_provider;
ALTER TABLE refunds ADD CONSTRAINT ck_refunds_provider CHECK (provider IN ('RAZORPAY', 'CASHFREE', 'MANUAL'));

-- Organisations with a gateway refund still waiting for Razorpay's answer, for the job that finishes them.
CREATE OR REPLACE FUNCTION tenants_with_pending_refunds() RETURNS SETOF uuid
    LANGUAGE sql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
    SELECT DISTINCT tenant_id FROM refunds
    WHERE provider = 'RAZORPAY' AND status = 'PENDING' AND created_at < now() - INTERVAL '2 minutes';
$$;
