-- A yes/no check keeps its expected answer as true or false. The template editor took free text, so some say "Yes"
-- or "No", which evaluation read as false: every good answer failed.
ALTER TABLE inspection_parameters NO FORCE ROW LEVEL SECURITY;
UPDATE inspection_parameters
SET expected_text = CASE WHEN lower(trim(expected_text)) IN ('true', 'yes', 'y') THEN 'true'
                         WHEN lower(trim(expected_text)) IN ('false', 'no', 'n') THEN 'false'
                    END
WHERE data_type = 'BOOLEAN' AND expected_text IS NOT NULL;
ALTER TABLE inspection_parameters FORCE ROW LEVEL SECURITY;
