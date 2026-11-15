-- A one-person organisation may close its own recalls and audits under its single-person review setting.
-- The service still requires a second person otherwise, and records each exception in the audit log as SELF_REVIEW.
ALTER TABLE recalls DROP CONSTRAINT ck_recalls_two_people;
ALTER TABLE audits DROP CONSTRAINT ck_audits_two_people;
