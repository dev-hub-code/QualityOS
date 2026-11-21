-- A day of the week a facility does not work in some weeks of each month, such as the 2nd and 4th Saturday.
-- alternate_off_day: Monday 1 ... Sunday 7. alternate_off_weeks: its 1st in the month 1, 2nd 2, 3rd 4, 4th 8, 5th 16.
ALTER TABLE facilities ADD COLUMN alternate_off_day smallint;
ALTER TABLE facilities ADD COLUMN alternate_off_weeks integer NOT NULL DEFAULT 0;
ALTER TABLE facilities ADD CONSTRAINT ck_facilities_alternate_off CHECK (
    (alternate_off_day IS NULL AND alternate_off_weeks = 0)
    OR (alternate_off_day BETWEEN 1 AND 7 AND alternate_off_weeks BETWEEN 1 AND 30));
