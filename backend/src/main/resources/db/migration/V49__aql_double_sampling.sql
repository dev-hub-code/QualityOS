-- AQL templates can use double sampling (ISO 2859-1 Tables III-A to III-C): a first sample that neither accepts nor
-- rejects the lot is followed by a second of the same size, and both are counted together.
ALTER TABLE inspection_template_versions
    ADD COLUMN double_sampling boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT ck_template_versions_double_sampling CHECK (NOT double_sampling OR sampling_type = 'AQL');

ALTER TABLE inspections ADD COLUMN second_sample_drawn boolean NOT NULL DEFAULT false;

-- The one change to an inspection's sampling after it starts: drawing its second sample, once, which adds exactly the
-- second sample its frozen specification names.
CREATE OR REPLACE FUNCTION protect_inspection_specification() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.spec_snapshot IS DISTINCT FROM OLD.spec_snapshot
        OR NEW.template_version_id IS DISTINCT FROM OLD.template_version_id
        OR (NEW.second_sample_drawn = OLD.second_sample_drawn AND NEW.sample_size IS DISTINCT FROM OLD.sample_size)
        OR (NEW.second_sample_drawn <> OLD.second_sample_drawn
            AND NOT (NEW.second_sample_drawn
                     AND NEW.sample_size = OLD.sample_size + (OLD.spec_snapshot -> 'secondSample' ->> 'sampleSize')::int))
    THEN
        RAISE EXCEPTION 'The specification of an inspection cannot be changed';
    END IF;
    RETURN NEW;
END;
$$;
