-- A template can sample by AQL: ISO 2859-1 single sampling for normal inspection, where the lot size and general
-- inspection level give the sample size, and the AQL the acceptance number, each time an inspection starts.
ALTER TABLE inspection_template_versions
    ADD COLUMN aql numeric(5,3),
    ADD COLUMN inspection_level text;

ALTER TABLE inspection_template_versions DROP CONSTRAINT ck_template_versions_sampling;
ALTER TABLE inspection_template_versions ADD CONSTRAINT ck_template_versions_sampling CHECK (
    (sampling_type = 'FIXED' AND sample_size > 0)
    OR (sampling_type = 'PERCENTAGE' AND sample_percent > 0 AND sample_percent <= 100)
    OR sampling_type = 'FULL'
    OR (sampling_type = 'AQL' AND aql > 0 AND inspection_level IN ('I', 'II', 'III')));
