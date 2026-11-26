-- Readings such as elongation, moisture or porosity are given in percent, which the shared units lacked.
ALTER TABLE units_of_measure NO FORCE ROW LEVEL SECURITY;
INSERT INTO units_of_measure (id, tenant_id, code, name, dimension, to_base)
VALUES ('0d3f6a52-6c1e-4b8e-9a51-2f7d9c41a0e3', NULL, '%', 'Percent', 'Ratio', NULL)
ON CONFLICT DO NOTHING;
ALTER TABLE units_of_measure FORCE ROW LEVEL SECURITY;
