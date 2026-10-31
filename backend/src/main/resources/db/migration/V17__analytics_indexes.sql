-- Analytics read decided inspections, and NCRs and CAPAs, by date.
CREATE INDEX ix_inspections_decided ON inspections (tenant_id, approved_at) WHERE final_result IS NOT NULL;
CREATE INDEX ix_ncr_created ON non_conformances (tenant_id, created_at);
CREATE INDEX ix_ncr_closed ON non_conformances (tenant_id, closed_at) WHERE closed_at IS NOT NULL;
CREATE INDEX ix_capa_created ON corrective_actions (tenant_id, created_at);
CREATE INDEX ix_capa_closed ON corrective_actions (tenant_id, closed_at) WHERE closed_at IS NOT NULL;
