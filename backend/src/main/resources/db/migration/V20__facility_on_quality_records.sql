-- NCRs, CAPAs and material corrections had no facility of their own, so a member limited to one plant could not
-- be kept to its records. Records that cannot be placed stay NULL, and a limited member never sees those.
ALTER TABLE non_conformances ADD COLUMN facility_id UUID;
ALTER TABLE corrective_actions ADD COLUMN facility_id UUID;
ALTER TABLE material_corrections ADD COLUMN facility_id UUID;

-- Row-level security binds the migration owner too, so the backfill has to step out of it.
ALTER TABLE non_conformances NO FORCE ROW LEVEL SECURITY;
ALTER TABLE corrective_actions NO FORCE ROW LEVEL SECURITY;
ALTER TABLE material_corrections NO FORCE ROW LEVEL SECURITY;
ALTER TABLE inspections NO FORCE ROW LEVEL SECURITY;
ALTER TABLE audits NO FORCE ROW LEVEL SECURITY;
ALTER TABLE material_receipts NO FORCE ROW LEVEL SECURITY;
ALTER TABLE material_receipt_items NO FORCE ROW LEVEL SECURITY;
ALTER TABLE production_batches NO FORCE ROW LEVEL SECURITY;
-- The new foreign keys must be able to read the facilities they check.
ALTER TABLE facilities NO FORCE ROW LEVEL SECURITY;

-- Finished records refuse every change. Placing them changes nothing they say, so the guard steps aside for these
-- updates alone and returns in this same transaction. So does updated_at, which keeps saying when a person last
-- changed the record.
ALTER TABLE non_conformances DISABLE TRIGGER trg_protect_finished;
ALTER TABLE corrective_actions DISABLE TRIGGER trg_protect_finished;
ALTER TABLE material_corrections DISABLE TRIGGER trg_protect_finished;
ALTER TABLE non_conformances DISABLE TRIGGER trg_updated_at;
ALTER TABLE corrective_actions DISABLE TRIGGER trg_updated_at;
ALTER TABLE material_corrections DISABLE TRIGGER trg_updated_at;

UPDATE non_conformances n SET facility_id = i.facility_id
FROM inspections i
WHERE i.tenant_id = n.tenant_id AND i.id = n.inspection_id;

UPDATE non_conformances n SET facility_id = a.facility_id
FROM audits a
WHERE a.tenant_id = n.tenant_id AND a.id = n.audit_id;

UPDATE corrective_actions c SET facility_id = n.facility_id
FROM non_conformances n
WHERE n.tenant_id = c.tenant_id AND n.id = c.ncr_id;

UPDATE material_corrections c SET facility_id = b.facility_id
FROM production_batches b
WHERE c.production_batch_id IS NOT NULL AND b.tenant_id = c.tenant_id AND b.id = c.production_batch_id;

UPDATE material_corrections c SET facility_id = r.facility_id
FROM material_receipt_items i
JOIN material_receipts r ON r.tenant_id = i.tenant_id AND r.id = i.receipt_id
WHERE c.production_batch_id IS NULL AND i.tenant_id = c.tenant_id AND i.id = c.receipt_item_id;

ALTER TABLE material_corrections ENABLE TRIGGER trg_updated_at;
ALTER TABLE corrective_actions ENABLE TRIGGER trg_updated_at;
ALTER TABLE non_conformances ENABLE TRIGGER trg_updated_at;
ALTER TABLE material_corrections ENABLE TRIGGER trg_protect_finished;
ALTER TABLE corrective_actions ENABLE TRIGGER trg_protect_finished;
ALTER TABLE non_conformances ENABLE TRIGGER trg_protect_finished;

ALTER TABLE non_conformances ADD CONSTRAINT fk_ncrs_facility
    FOREIGN KEY (tenant_id, facility_id) REFERENCES facilities (tenant_id, id);
ALTER TABLE corrective_actions ADD CONSTRAINT fk_capas_facility
    FOREIGN KEY (tenant_id, facility_id) REFERENCES facilities (tenant_id, id);
ALTER TABLE material_corrections ADD CONSTRAINT fk_corrections_facility
    FOREIGN KEY (tenant_id, facility_id) REFERENCES facilities (tenant_id, id);

ALTER TABLE facilities FORCE ROW LEVEL SECURITY;
ALTER TABLE production_batches FORCE ROW LEVEL SECURITY;
ALTER TABLE material_receipt_items FORCE ROW LEVEL SECURITY;
ALTER TABLE material_receipts FORCE ROW LEVEL SECURITY;
ALTER TABLE audits FORCE ROW LEVEL SECURITY;
ALTER TABLE inspections FORCE ROW LEVEL SECURITY;
ALTER TABLE material_corrections FORCE ROW LEVEL SECURITY;
ALTER TABLE corrective_actions FORCE ROW LEVEL SECURITY;
ALTER TABLE non_conformances FORCE ROW LEVEL SECURITY;

CREATE INDEX idx_ncrs_facility ON non_conformances (tenant_id, facility_id);
CREATE INDEX idx_capas_facility ON corrective_actions (tenant_id, facility_id);
CREATE INDEX idx_corrections_facility ON material_corrections (tenant_id, facility_id);
