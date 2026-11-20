-- The days of the week a facility never works, such as Sunday: Monday 1, Tuesday 2, Wednesday 4 ... Sunday 64.
ALTER TABLE facilities ADD COLUMN weekly_off_days integer NOT NULL DEFAULT 0;
ALTER TABLE facilities ADD CONSTRAINT ck_facilities_weekly_off_days CHECK (weekly_off_days BETWEEN 0 AND 127);
