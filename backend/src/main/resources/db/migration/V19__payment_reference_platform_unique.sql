-- A reference pays one invoice anywhere on the platform, however it was typed. The application checks this first, but
-- that check reads before it writes, so two payments arriving together could both pass it. Only the database can
-- refuse the second. A unique index sees every row, whatever row-level security shows to the session.

ALTER TABLE payment_transactions DROP CONSTRAINT uq_payments_reference;
DROP INDEX ix_payments_reference;
CREATE UNIQUE INDEX uq_payments_reference ON payment_transactions USING btree (upper(provider_reference));
