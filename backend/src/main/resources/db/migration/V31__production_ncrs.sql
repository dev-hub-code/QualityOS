-- Rejected and scrapped goods reported on an operation raise an NCR, so production defects reach the NCR list, the
-- Pareto and the category split. Each report can name its defect category. See docs/MANUFACTURING.md.

ALTER TABLE operation_reports
    ADD COLUMN defect_category_id uuid,
    ADD CONSTRAINT fk_operation_reports_defect_category
        FOREIGN KEY (tenant_id, defect_category_id) REFERENCES defect_categories(tenant_id, id);

CREATE INDEX ix_operation_reports_defect_category ON operation_reports (tenant_id, defect_category_id)
    WHERE defect_category_id IS NOT NULL;

ALTER TABLE manufacturing_settings
    -- On by default: a reject is a nonconformance, and an organisation that tracks them elsewhere can turn it off.
    ADD COLUMN rejection_raises_ncr boolean DEFAULT true NOT NULL;

ALTER TABLE non_conformances
    ADD COLUMN work_order_id uuid,
    ADD COLUMN operation_report_id uuid;

ALTER TABLE non_conformances DROP CONSTRAINT ck_ncr_source;
ALTER TABLE non_conformances
    ADD CONSTRAINT ck_ncr_source CHECK (source IN ('INSPECTION', 'MANUAL', 'AUDIT', 'CALIBRATION', 'PRODUCTION')),
    ADD CONSTRAINT ck_ncr_production_source
        CHECK ((source = 'PRODUCTION') = (operation_report_id IS NOT NULL AND work_order_id IS NOT NULL)),
    ADD CONSTRAINT fk_ncr_work_order FOREIGN KEY (tenant_id, work_order_id) REFERENCES work_orders(tenant_id, id),
    ADD CONSTRAINT fk_ncr_operation_report
        FOREIGN KEY (tenant_id, operation_report_id) REFERENCES operation_reports(tenant_id, id);

-- One NCR per report.
CREATE UNIQUE INDEX uq_ncr_operation_report ON non_conformances (tenant_id, operation_report_id)
    WHERE operation_report_id IS NOT NULL;
CREATE INDEX ix_ncr_work_order ON non_conformances (tenant_id, work_order_id) WHERE work_order_id IS NOT NULL;
