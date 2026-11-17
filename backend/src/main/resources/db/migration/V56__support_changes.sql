-- An organisation can let support make changes as well as read, if it chooses to. Changes are allowed for at most 24
-- hours, and some decisions stay with the organisation's own people whatever it grants (see SupportAccessService).
ALTER TABLE support_access_grants ADD COLUMN allow_changes boolean NOT NULL DEFAULT false;
ALTER TABLE support_access_grants ADD CONSTRAINT ck_support_access_grants_change_window
    CHECK (NOT allow_changes OR expires_at <= granted_at + INTERVAL '24 hours');

-- Each change support made, beside what it read.
ALTER TABLE support_access_events DROP CONSTRAINT ck_support_access_events_kind;
ALTER TABLE support_access_events ADD CONSTRAINT ck_support_access_events_kind
    CHECK (kind IN ('STARTED', 'VIEWED', 'CHANGED'));

-- Marks an audit entry made in a support session, so it is never taken for a member's own work.
ALTER TABLE audit_logs ADD COLUMN support_grant_id uuid;
