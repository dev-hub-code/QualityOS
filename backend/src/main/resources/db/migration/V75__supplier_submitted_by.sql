-- Who sent a supplier for approval, so the approval can be made by someone else. Suppliers already waiting have no
-- recorded submitter and can be approved by anyone who may approve.
ALTER TABLE suppliers ADD COLUMN submitted_by uuid;
ALTER TABLE suppliers ADD COLUMN submitted_at timestamp with time zone;
