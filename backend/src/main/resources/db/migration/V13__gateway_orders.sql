-- A checkout started with a payment gateway. Money only reaches an invoice through payment_transactions.
CREATE TABLE gateway_orders (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    invoice_id uuid NOT NULL,
    provider text NOT NULL,
    gateway_order_id text NOT NULL,
    amount numeric(12,2) NOT NULL,
    status text NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT gateway_orders_pkey PRIMARY KEY (id),
    CONSTRAINT uq_gateway_orders_reference UNIQUE (provider, gateway_order_id),
    CONSTRAINT ck_gateway_orders_amount CHECK (amount > 0),
    CONSTRAINT ck_gateway_orders_status CHECK (status = ANY (ARRAY['ACTIVE'::text, 'PAID'::text, 'NEEDS_REVIEW'::text])),
    CONSTRAINT gateway_orders_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_gateway_orders_invoice FOREIGN KEY (tenant_id, invoice_id) REFERENCES subscription_invoices(tenant_id, id)
);

CREATE INDEX ix_gateway_orders_invoice ON gateway_orders USING btree (tenant_id, invoice_id);

CREATE TRIGGER trg_updated_at BEFORE UPDATE ON gateway_orders FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE gateway_orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY gateway_orders FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON gateway_orders USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));
CREATE POLICY gateway_order_scan ON gateway_orders FOR SELECT TO "${flyway:user}" USING (true);

GRANT SELECT,INSERT,UPDATE ON TABLE gateway_orders TO ${approle};

-- A webhook arrives with no signed-in tenant. Its signature has been checked first, and this returns the id alone.
CREATE FUNCTION gateway_order_tenant(p_provider text, p_order text) RETURNS uuid
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
    SELECT tenant_id FROM gateway_orders WHERE provider = p_provider AND gateway_order_id = p_order;
$$;

REVOKE ALL ON FUNCTION gateway_order_tenant(p_provider text, p_order text) FROM PUBLIC;
GRANT ALL ON FUNCTION gateway_order_tenant(p_provider text, p_order text) TO ${approle};
