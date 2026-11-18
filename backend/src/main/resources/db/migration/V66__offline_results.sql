-- When each result was measured, which for one recorded offline is earlier than when it reached the server. A result
-- sent late never replaces one measured after it.
ALTER TABLE inspection_results ADD COLUMN measured_at timestamp with time zone;

-- Results already recorded were measured when last saved. Filling that in is not a change to a submitted result, and
-- leaves when it was saved as it was.
ALTER TABLE inspection_results NO FORCE ROW LEVEL SECURITY;
ALTER TABLE inspection_results DISABLE TRIGGER trg_protect_results;
ALTER TABLE inspection_results DISABLE TRIGGER trg_updated_at;
UPDATE inspection_results SET measured_at = updated_at;
ALTER TABLE inspection_results ENABLE TRIGGER trg_updated_at;
ALTER TABLE inspection_results ENABLE TRIGGER trg_protect_results;
ALTER TABLE inspection_results FORCE ROW LEVEL SECURITY;

ALTER TABLE inspection_results ALTER COLUMN measured_at SET NOT NULL;
