-- Two SECURITY DEFINER lookups read across organisations for callers that have none: the email worker and the payment
-- webhook. A session signed in to an organisation now gets nothing through them that its own row security would hide.

-- An organisation's own logo, or any logo when no organisation is set.
CREATE OR REPLACE FUNCTION tenant_logo(p_tenant uuid) RETURNS TABLE(content_type text, data bytea)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
    SELECT l.content_type, l.data FROM tenant_logos l
    WHERE l.tenant_id = p_tenant AND (current_tenant_id() IS NULL OR current_tenant_id() = p_tenant)
$$;

-- Only before a tenant is known: a signed-in session reads its own orders through row security instead.
CREATE OR REPLACE FUNCTION gateway_order_tenant(p_provider text, p_order text) RETURNS uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
    SELECT tenant_id FROM gateway_orders
    WHERE provider = p_provider AND gateway_order_id = p_order AND current_tenant_id() IS NULL;
$$;
