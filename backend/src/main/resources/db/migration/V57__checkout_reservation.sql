-- An order is reserved (CREATING) under the invoice's row lock before Cashfree is asked to create it, so two checkouts
-- at once share one order instead of making two. ABANDONED: Cashfree has no such order, or it expired unpaid there.
ALTER TABLE gateway_orders DROP CONSTRAINT ck_gateway_orders_status;
ALTER TABLE gateway_orders ADD CONSTRAINT ck_gateway_orders_status
    CHECK (status IN ('CREATING', 'ACTIVE', 'PAID', 'NEEDS_REVIEW', 'ABANDONED'));

-- At most one order being created for an invoice, whatever the application does.
CREATE UNIQUE INDEX uq_gateway_orders_creating ON gateway_orders (tenant_id, invoice_id) WHERE status = 'CREATING';
