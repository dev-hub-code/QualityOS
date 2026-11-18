-- A plan changed mid-period is charged its list price less the unused value of what was already paid. A credit larger
-- than the price becomes extra days on the new period, and an invoice it covers whole is settled at once.
ALTER TABLE subscription_invoices
    ADD COLUMN list_price numeric(12,2),
    ADD COLUMN proration_credit numeric(12,2),
    ADD COLUMN proration_note text,
    ADD COLUMN extra_days integer NOT NULL DEFAULT 0,
    ADD COLUMN renews_period_ending timestamptz,
    ADD CONSTRAINT ck_subscription_invoices_credit CHECK (proration_credit IS NULL OR (proration_credit > 0
        AND list_price IS NOT NULL AND amount = GREATEST(list_price - proration_credit, 0)
        AND char_length(proration_note) BETWEEN 1 AND 500)),
    ADD CONSTRAINT ck_subscription_invoices_extra_days CHECK (extra_days >= 0);

-- A period is renewed by one invoice raised on its own, even if that invoice is later replaced.
CREATE UNIQUE INDEX ux_subscription_invoices_renewal
    ON subscription_invoices (tenant_id, renews_period_ending) WHERE renews_period_ending IS NOT NULL;

-- Refund and renewal notices are billing notices too.
CREATE OR REPLACE FUNCTION platform_notification_page(p_type text, p_limit integer, p_offset integer) RETURNS TABLE(id uuid, sent_at timestamp with time zone, type text, title text, organisation text, tenant_id uuid, recipient text, email_status text, total bigint)
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
#variable_conflict use_column
BEGIN
    PERFORM require_platform_admin();
    RETURN QUERY
    SELECT n.id, n.created_at, n.type, n.title, t.name, n.tenant_id, u.email, n.email_status, count(*) OVER ()
    FROM notifications n
    JOIN tenants t ON t.id = n.tenant_id
    LEFT JOIN users u ON u.id = n.user_id
    WHERE n.type = ANY (ARRAY['SUBSCRIPTION_EXPIRY', 'PAYMENT_RECEIVED', 'INVOICE_OVERDUE', 'PLAN_CHANGED',
                              'REFUND_ISSUED', 'RENEWAL_INVOICE'])
      AND (p_type IS NULL OR n.type = p_type)
    ORDER BY n.created_at DESC, n.id DESC
    LIMIT p_limit OFFSET p_offset;
END;
$$;
