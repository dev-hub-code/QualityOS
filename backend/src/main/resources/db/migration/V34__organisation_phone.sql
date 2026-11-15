-- An organisation's contact number, for the Control Center and for support to call back.
ALTER TABLE tenants ADD COLUMN contact_phone text;
ALTER TABLE tenants ADD CONSTRAINT ck_tenants_contact_phone CHECK (contact_phone ~ '^\+?[0-9 ()-]{6,20}$');
