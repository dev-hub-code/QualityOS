-- A credit note takes back part or all of a paid invoice, with its GST, and money goes back with it as a refund.
-- A payment the gateway took but could not apply to an invoice is refunded too, without a credit note: it was never
-- revenue. Both are documents of record, so neither can be changed once written; a refund's status moves on its own.
CREATE TABLE credit_notes (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    credit_note_number text NOT NULL,
    invoice_id uuid NOT NULL,
    amount numeric(12,2) NOT NULL,
    tax_amount numeric(12,2) NOT NULL,
    cgst_amount numeric(12,2),
    sgst_amount numeric(12,2),
    igst_amount numeric(12,2),
    total numeric(12,2) NOT NULL,
    reason text NOT NULL,
    issued_on date NOT NULL,
    ends_access boolean NOT NULL DEFAULT false,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT credit_notes_pkey PRIMARY KEY (id),
    CONSTRAINT uq_credit_notes_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_credit_notes_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_credit_notes_invoice FOREIGN KEY (tenant_id, invoice_id) REFERENCES subscription_invoices (tenant_id, id),
    CONSTRAINT ck_credit_notes_amount CHECK (amount > 0 AND tax_amount >= 0 AND total = amount + tax_amount),
    CONSTRAINT ck_credit_notes_reason CHECK (length(btrim(reason)) BETWEEN 1 AND 1000)
);
CREATE UNIQUE INDEX uq_credit_notes_number ON credit_notes (tenant_id, credit_note_number);
CREATE INDEX ix_credit_notes_invoice ON credit_notes (tenant_id, invoice_id);
CREATE TRIGGER trg_credit_notes_history BEFORE DELETE OR UPDATE ON credit_notes FOR EACH ROW EXECUTE FUNCTION reject_change();
ALTER TABLE credit_notes ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY credit_notes FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON credit_notes
    USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

ALTER TABLE gateway_orders ADD CONSTRAINT uq_gateway_orders_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE gateway_orders DROP CONSTRAINT ck_gateway_orders_status,
    ADD CONSTRAINT ck_gateway_orders_status
        CHECK (status IN ('CREATING', 'ACTIVE', 'PAID', 'NEEDS_REVIEW', 'ABANDONED', 'REFUNDED'));

CREATE TABLE refunds (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    credit_note_id uuid,
    gateway_order_id uuid,
    provider text NOT NULL,
    refund_reference text NOT NULL,
    gateway_refund_id text,
    amount numeric(12,2) NOT NULL,
    status text NOT NULL,
    failure_reason text,
    refunded_on date,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT refunds_pkey PRIMARY KEY (id),
    CONSTRAINT fk_refunds_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_refunds_credit_note FOREIGN KEY (tenant_id, credit_note_id) REFERENCES credit_notes (tenant_id, id),
    CONSTRAINT fk_refunds_gateway_order FOREIGN KEY (tenant_id, gateway_order_id) REFERENCES gateway_orders (tenant_id, id),
    CONSTRAINT ck_refunds_for CHECK (num_nonnulls(credit_note_id, gateway_order_id) = 1),
    CONSTRAINT ck_refunds_provider CHECK (provider IN ('CASHFREE', 'MANUAL')),
    CONSTRAINT ck_refunds_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_refunds_amount CHECK (amount > 0),
    CONSTRAINT ck_refunds_reference CHECK (length(btrim(refund_reference)) BETWEEN 1 AND 100),
    CONSTRAINT ck_refunds_done CHECK ((status = 'SUCCEEDED') = (refunded_on IS NOT NULL))
);
-- A bank or gateway reference refunds once, across the platform.
CREATE UNIQUE INDEX uq_refunds_reference ON refunds (provider, upper(refund_reference));
CREATE INDEX ix_refunds_credit_note ON refunds (tenant_id, credit_note_id);
CREATE INDEX ix_refunds_gateway_order ON refunds (tenant_id, gateway_order_id);
CREATE TRIGGER trg_updated_at BEFORE UPDATE ON refunds FOR EACH ROW EXECUTE FUNCTION set_updated_at();
ALTER TABLE refunds ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY refunds FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON refunds
    USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

-- Payments the gateway took that no invoice could take, across organisations, for a platform administrator to settle.
CREATE FUNCTION platform_unapplied_payments() RETURNS TABLE(tenant_id uuid, organisation text, order_id uuid,
        gateway_order_id text, invoice_number text, amount numeric, since timestamp with time zone)
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
BEGIN
    PERFORM require_platform_admin();
    RETURN QUERY
    SELECT o.tenant_id, t.name, o.id, o.gateway_order_id, i.invoice_number, o.amount, o.updated_at
    FROM gateway_orders o
    JOIN tenants t ON t.id = o.tenant_id
    JOIN subscription_invoices i ON i.id = o.invoice_id
    WHERE o.status = 'NEEDS_REVIEW'
    ORDER BY o.updated_at;
END;
$$;
REVOKE ALL ON FUNCTION platform_unapplied_payments() FROM PUBLIC;
GRANT ALL ON FUNCTION platform_unapplied_payments() TO ${approle};
