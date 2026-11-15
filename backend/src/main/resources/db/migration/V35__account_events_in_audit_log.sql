-- Account-level events (verification, password reset, failed and locked sign-ins) have no tenant, because they belong
-- to a person, not an organisation. An organisation may read those of its own members from the day they joined, so
-- its administrators see them in the audit log. Other tenantless rows stay hidden.
CREATE POLICY account_events_read ON audit_logs FOR SELECT USING (
    tenant_id IS NULL
    AND entity_type = 'User'
    AND action IN ('EMAIL_VERIFICATION_SENT', 'EMAIL_VERIFIED', 'PASSWORD_RESET_REQUESTED', 'PASSWORD_RESET',
                   'PASSWORD_CHANGED', 'LOGIN_FAILED', 'USER_LOCKED', 'PENDING_VERIFICATION_LOGIN',
                   'EMAIL_LANGUAGE_CHANGED')
    AND EXISTS (SELECT 1 FROM tenant_memberships m
                WHERE m.tenant_id = current_tenant_id()
                  AND m.user_id::text = audit_logs.entity_id
                  AND m.created_at <= audit_logs.occurred_at));
