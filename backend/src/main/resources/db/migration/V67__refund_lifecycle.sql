-- A payment no invoice could take waits in REFUND_PENDING from the moment its refund is asked for until Cashfree says
-- it went through (REFUNDED) or failed (back to NEEDS_REVIEW, to be refunded again or settled otherwise).
ALTER TABLE gateway_orders DROP CONSTRAINT ck_gateway_orders_status,
    ADD CONSTRAINT ck_gateway_orders_status
        CHECK (status IN ('CREATING', 'ACTIVE', 'PAID', 'NEEDS_REVIEW', 'ABANDONED', 'REFUND_PENDING', 'REFUNDED'));

-- The functions below look across organisations, as the Flyway user that owns them.
CREATE POLICY refund_scan ON refunds FOR SELECT TO "${flyway:user}" USING (true);

-- Payments waiting for a decision, and those whose refund is under way, with how the latest refund stands.
DROP FUNCTION platform_unapplied_payments();
CREATE FUNCTION platform_unapplied_payments() RETURNS TABLE(tenant_id uuid, organisation text, order_id uuid,
        gateway_order_id text, invoice_number text, amount numeric, since timestamp with time zone, status text,
        refund_status text, refund_failure text)
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
BEGIN
    PERFORM require_platform_admin();
    RETURN QUERY
    SELECT o.tenant_id, t.name, o.id, o.gateway_order_id, i.invoice_number, o.amount, o.updated_at, o.status,
           r.status, r.failure_reason
    FROM gateway_orders o
    JOIN tenants t ON t.id = o.tenant_id
    JOIN subscription_invoices i ON i.id = o.invoice_id
    LEFT JOIN LATERAL (SELECT f.status, f.failure_reason FROM refunds f
                       WHERE f.tenant_id = o.tenant_id AND f.gateway_order_id = o.id
                       ORDER BY f.created_at DESC LIMIT 1) r ON true
    WHERE o.status IN ('NEEDS_REVIEW', 'REFUND_PENDING')
    ORDER BY o.updated_at;
END;
$$;
REVOKE ALL ON FUNCTION platform_unapplied_payments() FROM PUBLIC;
GRANT ALL ON FUNCTION platform_unapplied_payments() TO ${approle};

-- Organisations with a gateway refund still waiting for Cashfree's answer, for the job that finishes them.
CREATE FUNCTION tenants_with_pending_refunds() RETURNS SETOF uuid
    LANGUAGE sql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
    SELECT DISTINCT tenant_id FROM refunds
    WHERE provider = 'CASHFREE' AND status = 'PENDING' AND created_at < now() - INTERVAL '2 minutes';
$$;
REVOKE ALL ON FUNCTION tenants_with_pending_refunds() FROM PUBLIC;
GRANT ALL ON FUNCTION tenants_with_pending_refunds() TO ${approle};
