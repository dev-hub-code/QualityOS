-- An organisation may require a file attached to a CAPA action before the action can be completed.
ALTER TABLE tenant_settings ADD COLUMN action_evidence_required boolean DEFAULT false NOT NULL;
