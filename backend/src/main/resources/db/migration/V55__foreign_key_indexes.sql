-- Foreign keys the application filters or joins on, which had no index leading with them (after tenant_id).
-- Found by docs/AUDIT_BACKEND_2026-11-17.md; columns only checked when a parent row is deleted are left out.
-- Each leads with tenant_id, as every query does under RLS, except a plan's subscriptions and invoices, which the
-- platform counts across organisations.

CREATE INDEX ix_audit_findings_ncr_id ON audit_findings (tenant_id, ncr_id);
CREATE INDEX ix_audits_checklist_id ON audits (tenant_id, checklist_id);
CREATE INDEX ix_audits_facility_id ON audits (tenant_id, facility_id);
CREATE INDEX ix_audits_product_id ON audits (tenant_id, product_id);
CREATE INDEX ix_audits_supplier_id ON audits (tenant_id, supplier_id);
CREATE INDEX ix_bom_lines_material_id ON bom_lines (tenant_id, material_id);
CREATE INDEX ix_calibration_impact_reviews_inspection_id ON calibration_impact_reviews (tenant_id, inspection_id);
CREATE INDEX ix_dispatch_items_dispatch_id ON dispatch_items (tenant_id, dispatch_id);
CREATE INDEX ix_dispatch_records_customer_id ON dispatch_records (tenant_id, customer_id);
CREATE INDEX ix_dispatch_reversal_items_production_batch_id ON dispatch_reversal_items (tenant_id, production_batch_id);
CREATE INDEX ix_dispatch_reversal_items_reversal_id ON dispatch_reversal_items (tenant_id, reversal_id);
CREATE INDEX ix_downtime_records_work_center_id ON downtime_records (tenant_id, work_center_id);
CREATE INDEX ix_downtime_records_work_order_id ON downtime_records (tenant_id, work_order_id);
CREATE INDEX ix_inspection_templates_material_id ON inspection_templates (tenant_id, material_id);
CREATE INDEX ix_inspection_templates_product_id ON inspection_templates (tenant_id, product_id);
CREATE INDEX ix_inspections_facility_id ON inspections (tenant_id, facility_id);
CREATE INDEX ix_inspections_material_id ON inspections (tenant_id, material_id);
CREATE INDEX ix_material_receipt_items_material_id ON material_receipt_items (tenant_id, material_id);
CREATE INDEX ix_material_receipts_facility_id ON material_receipts (tenant_id, facility_id);
CREATE INDEX ix_material_receipts_supplier_id ON material_receipts (tenant_id, supplier_id);
CREATE INDEX ix_membership_facilities_facility_id ON membership_facilities (tenant_id, facility_id);
CREATE INDEX ix_non_conformances_audit_id ON non_conformances (tenant_id, audit_id);
CREATE INDEX ix_non_conformances_instrument_id ON non_conformances (tenant_id, instrument_id);
CREATE INDEX ix_non_conformances_material_id ON non_conformances (tenant_id, material_id);
CREATE INDEX ix_non_conformances_supplier_id ON non_conformances (tenant_id, supplier_id);
CREATE INDEX ix_operation_events_machine_id ON operation_events (tenant_id, machine_id);
CREATE INDEX ix_operation_events_work_order_id ON operation_events (tenant_id, work_order_id);
CREATE INDEX ix_operation_measurements_work_order_id ON operation_measurements (tenant_id, work_order_id);
CREATE INDEX ix_operation_reports_work_order_id ON operation_reports (tenant_id, work_order_id);
CREATE INDEX ix_product_materials_material_id ON product_materials (tenant_id, material_id);
CREATE INDEX ix_production_plan_lines_product_id ON production_plan_lines (tenant_id, product_id);
CREATE INDEX ix_purchase_orders_facility_id ON purchase_orders (tenant_id, facility_id);
CREATE INDEX ix_quality_checkpoints_product_id ON quality_checkpoints (tenant_id, product_id);
CREATE INDEX ix_quality_checkpoints_template_id ON quality_checkpoints (tenant_id, template_id);
CREATE INDEX ix_quality_checkpoints_work_center_id ON quality_checkpoints (tenant_id, work_center_id);
CREATE INDEX ix_recall_batches_production_batch_id ON recall_batches (tenant_id, production_batch_id);
CREATE INDEX ix_recall_contact_records_customer_id ON recall_contact_records (tenant_id, customer_id);
CREATE INDEX ix_recalls_capa_id ON recalls (tenant_id, capa_id);
CREATE INDEX ix_rework_order_steps_work_center_id ON rework_order_steps (tenant_id, work_center_id);
CREATE INDEX ix_rework_orders_ncr_id ON rework_orders (tenant_id, ncr_id);
CREATE INDEX ix_rework_orders_operation_id ON rework_orders (tenant_id, operation_id);
CREATE INDEX ix_rework_orders_production_batch_id ON rework_orders (tenant_id, production_batch_id);
CREATE INDEX ix_subscription_invoices_plan_id ON subscription_invoices (plan_id);
CREATE INDEX ix_subscriptions_plan_id ON subscriptions (plan_id);
CREATE INDEX ix_work_order_material_movements_batch_relationship_id ON work_order_material_movements (tenant_id, batch_relationship_id);
CREATE INDEX ix_work_order_materials_component_product_id ON work_order_materials (tenant_id, component_product_id);
CREATE INDEX ix_work_order_materials_material_id ON work_order_materials (tenant_id, material_id);
CREATE INDEX ix_work_order_operations_work_center_id ON work_order_operations (tenant_id, work_center_id);
CREATE INDEX ix_work_order_quality_gates_operation_id ON work_order_quality_gates (tenant_id, operation_id);
CREATE INDEX ix_work_order_quality_gates_template_id ON work_order_quality_gates (tenant_id, template_id);
CREATE INDEX ix_work_orders_bom_id ON work_orders (tenant_id, bom_id);
CREATE INDEX ix_work_orders_routing_id ON work_orders (tenant_id, routing_id);
