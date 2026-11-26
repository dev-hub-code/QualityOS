-- An operator ending a shift pauses for the hand-over: planned, and the machine is not broken down.
ALTER TABLE operation_events DROP CONSTRAINT ck_operation_events_loss;
ALTER TABLE operation_events ADD CONSTRAINT ck_operation_events_loss CHECK (loss_category IS NULL OR loss_category IN (
    'MACHINE_BREAKDOWN', 'MATERIAL_SHORTAGE', 'POWER_FAILURE', 'QUALITY_ISSUE', 'OPERATOR_UNAVAILABLE',
    'PLANNED_MAINTENANCE', 'CHANGEOVER', 'ORDER_ON_HOLD', 'SHIFT_END', 'OTHER'));
ALTER TABLE downtime_records DROP CONSTRAINT ck_downtime_records_category;
ALTER TABLE downtime_records ADD CONSTRAINT ck_downtime_records_category CHECK (category IN ('MACHINE_BREAKDOWN',
    'MATERIAL_SHORTAGE', 'POWER_FAILURE', 'QUALITY_ISSUE', 'OPERATOR_UNAVAILABLE', 'PLANNED_MAINTENANCE', 'CHANGEOVER',
    'ORDER_ON_HOLD', 'SHIFT_END', 'OTHER'));
