-- Every organisation starts on the same trial, so its length has one home: platform_settings.default_trial_days.
ALTER TABLE subscription_plans DROP CONSTRAINT ck_plan_days;
ALTER TABLE subscription_plans DROP COLUMN trial_days;
ALTER TABLE subscription_plans ADD CONSTRAINT ck_plan_grace_days CHECK (grace_days >= 0);
