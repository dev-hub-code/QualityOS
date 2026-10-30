-- Tenants, users, memberships, RBAC, refresh tokens and the audit log, with row-level security.

-- User acting in the current transaction, NULL when none. Lets a user see their own memberships before
-- a tenant has been chosen.
CREATE FUNCTION current_app_user_id() RETURNS uuid
    LANGUAGE sql STABLE AS
$$
SELECT NULLIF(current_setting('app.user_id', true), '')::uuid;
$$;

CREATE TABLE tenants (
    id            UUID PRIMARY KEY,
    name          TEXT        NOT NULL,
    legal_name    TEXT,
    gstin         TEXT,
    industry      TEXT,
    country_code  TEXT        NOT NULL DEFAULT 'IN',
    timezone      TEXT        NOT NULL DEFAULT 'Asia/Kolkata',
    currency      TEXT        NOT NULL DEFAULT 'INR',
    locale        TEXT        NOT NULL DEFAULT 'en-IN',
    address       TEXT,
    onboarded_at  TIMESTAMPTZ,
    status        TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    UUID,
    updated_by    UUID,
    version       BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_tenants_status CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT ck_tenants_gstin CHECK (gstin IS NULL OR gstin ~ '^[0-9]{2}[A-Z0-9]{13}$')
);

-- Emails are normalised by the application; the CHECK guarantees uniqueness is case-insensitive.
CREATE TABLE users (
    id                  UUID PRIMARY KEY,
    email               TEXT        NOT NULL,
    password_hash       TEXT        NOT NULL,
    full_name           TEXT        NOT NULL,
    status              TEXT        NOT NULL DEFAULT 'ACTIVE',
    failed_login_count  INTEGER     NOT NULL DEFAULT 0,
    locked_until        TIMESTAMPTZ,
    last_login_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_lowercase CHECK (email = lower(email)),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE tenant_memberships (
    id          UUID PRIMARY KEY,
    tenant_id   UUID        NOT NULL REFERENCES tenants (id),
    user_id     UUID        NOT NULL REFERENCES users (id),
    status      TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  UUID,
    updated_by  UUID,
    version     BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_tenant_memberships_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_tenant_memberships_tenant_user UNIQUE (tenant_id, user_id),
    CONSTRAINT ck_tenant_memberships_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);
CREATE INDEX ix_tenant_memberships_user ON tenant_memberships (user_id);

CREATE TABLE permissions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        TEXT NOT NULL,
    module      TEXT NOT NULL,
    description TEXT NOT NULL,
    CONSTRAINT uq_permissions_code UNIQUE (code)
);

-- tenant_id is NULL for the system defaults shared by all tenants, which tenants cannot change.
CREATE TABLE roles (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID REFERENCES tenants (id),
    code        TEXT        NOT NULL,
    name        TEXT        NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  UUID,
    updated_by  UUID,
    version     BIGINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_roles_system_code ON roles (code) WHERE tenant_id IS NULL;
CREATE UNIQUE INDEX uq_roles_tenant_code ON roles (tenant_id, code) WHERE tenant_id IS NOT NULL;

CREATE TABLE role_permissions (
    role_id       UUID NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES permissions (id),
    PRIMARY KEY (role_id, permission_id)
);
CREATE INDEX ix_role_permissions_permission ON role_permissions (permission_id);

CREATE TABLE user_roles (
    id             UUID PRIMARY KEY,
    tenant_id      UUID        NOT NULL,
    membership_id  UUID        NOT NULL,
    role_id        UUID        NOT NULL REFERENCES roles (id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     UUID,
    updated_by     UUID,
    version        BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT fk_user_roles_membership FOREIGN KEY (tenant_id, membership_id)
        REFERENCES tenant_memberships (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT uq_user_roles_membership_role UNIQUE (membership_id, role_id)
);
CREATE INDEX ix_user_roles_tenant_role ON user_roles (tenant_id, role_id);

-- Reached only through the token hash by the auth service, before any tenant is known: no RLS.
CREATE TABLE refresh_tokens (
    id           UUID PRIMARY KEY,
    user_id      UUID        NOT NULL REFERENCES users (id),
    tenant_id    UUID        NOT NULL REFERENCES tenants (id),
    family_id    UUID        NOT NULL,
    token_hash   TEXT        NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    consumed_at  TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   UUID,
    updated_by   UUID,
    version      BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash)
);
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);

-- tenant_id is NULL for events with no tenant, such as a failed login.
CREATE TABLE audit_logs (
    id              UUID PRIMARY KEY,
    tenant_id       UUID REFERENCES tenants (id),
    user_id         UUID,
    action          TEXT        NOT NULL,
    entity_type     TEXT        NOT NULL,
    entity_id       TEXT,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    ip_address      TEXT,
    correlation_id  TEXT,
    previous_state  JSONB,
    new_state       JSONB
);
CREATE INDEX ix_audit_logs_tenant_time ON audit_logs (tenant_id, occurred_at DESC);
CREATE INDEX ix_audit_logs_tenant_entity ON audit_logs (tenant_id, entity_type, entity_id);

-- Append-only and read-only tables for the application role.
REVOKE UPDATE, DELETE ON audit_logs FROM ${approle};
REVOKE INSERT, UPDATE, DELETE ON permissions FROM ${approle};

CREATE TRIGGER trg_tenants_updated_at BEFORE UPDATE ON tenants
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_users_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_tenant_memberships_updated_at BEFORE UPDATE ON tenant_memberships
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_roles_updated_at BEFORE UPDATE ON roles
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_refresh_tokens_updated_at BEFORE UPDATE ON refresh_tokens
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

INSERT INTO permissions (code, module, description) VALUES
    ('TENANT_MANAGE',       'Organisation', 'Change organisation profile and settings'),
    ('FACILITY_VIEW',       'Organisation', 'View facilities and departments'),
    ('FACILITY_MANAGE',     'Organisation', 'Manage facilities, departments, lines and shifts'),
    ('USER_MANAGE',         'Users',        'Add and deactivate users, assign roles'),
    ('ROLE_MANAGE',         'Users',        'Create and change custom roles'),
    ('AUDIT_LOG_VIEW',      'Users',        'View the audit trail'),
    ('SUPPLIER_VIEW',       'Suppliers',    'View suppliers'),
    ('SUPPLIER_CREATE',     'Suppliers',    'Register suppliers'),
    ('SUPPLIER_UPDATE',     'Suppliers',    'Change suppliers'),
    ('SUPPLIER_APPROVE',    'Suppliers',    'Approve or block suppliers'),
    ('PRODUCT_VIEW',        'Products',     'View products and materials'),
    ('PRODUCT_MANAGE',      'Products',     'Manage products, materials and specifications'),
    ('PURCHASE_VIEW',       'Purchasing',   'View purchase orders and receipts'),
    ('PURCHASE_MANAGE',     'Purchasing',   'Manage purchase orders'),
    ('RECEIPT_MANAGE',      'Purchasing',   'Register material receipts'),
    ('TEMPLATE_VIEW',       'Inspection',   'View inspection templates'),
    ('TEMPLATE_MANAGE',     'Inspection',   'Create and change draft templates'),
    ('TEMPLATE_PUBLISH',    'Inspection',   'Approve and publish templates'),
    ('INSPECTION_VIEW',     'Inspection',   'View inspections'),
    ('INSPECTION_CREATE',   'Inspection',   'Create inspections'),
    ('INSPECTION_EXECUTE',  'Inspection',   'Record inspection results'),
    ('INSPECTION_APPROVE',  'Inspection',   'Approve or reject inspections'),
    ('DISPOSITION_RECORD',  'Inspection',   'Record material disposition'),
    ('NCR_VIEW',            'Quality',      'View non-conformances'),
    ('NCR_CREATE',          'Quality',      'Raise non-conformances'),
    ('NCR_ASSIGN',          'Quality',      'Assign and progress non-conformances'),
    ('NCR_CLOSE',           'Quality',      'Approve and close non-conformances'),
    ('CAPA_VIEW',           'Quality',      'View corrective actions'),
    ('CAPA_MANAGE',         'Quality',      'Create and progress corrective actions'),
    ('CAPA_APPROVE',        'Quality',      'Verify and close corrective actions'),
    ('TRACEABILITY_VIEW',   'Traceability', 'View batch traceability'),
    ('TRACEABILITY_MANAGE', 'Traceability', 'Register batches and dispatches'),
    ('CALIBRATION_VIEW',    'Calibration',  'View instruments'),
    ('CALIBRATION_MANAGE',  'Calibration',  'Manage instruments and calibrations'),
    ('AUDIT_VIEW',          'Audit',        'View quality audits'),
    ('AUDIT_MANAGE',        'Audit',        'Plan and run quality audits'),
    ('REPORT_VIEW',         'Reporting',    'View analytics and reports'),
    ('SUBSCRIPTION_MANAGE', 'Subscription', 'Manage the subscription');

INSERT INTO roles (code, name, description) VALUES
    ('ORGANISATION_OWNER', 'Organisation Owner', 'Full control of the organisation'),
    ('ORGANISATION_ADMIN', 'Organisation Admin', 'Administers the organisation and its users'),
    ('PLANT_MANAGER',      'Plant Manager',      'Oversees plant quality operations'),
    ('QUALITY_MANAGER',    'Quality Manager',    'Owns quality processes and approvals'),
    ('QUALITY_ENGINEER',   'Quality Engineer',   'Configures inspections and investigates issues'),
    ('QUALITY_INSPECTOR',  'Quality Inspector',  'Performs inspections'),
    ('SUPPLIER_MANAGER',   'Supplier Manager',   'Manages suppliers and purchasing'),
    ('AUDITOR',            'Auditor',            'Read access plus quality audits'),
    ('VIEWER',             'Viewer',             'Read-only access');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON
    r.code = 'ORGANISATION_OWNER'
    OR (r.code = 'ORGANISATION_ADMIN' AND p.code <> 'SUBSCRIPTION_MANAGE')
    OR (r.code = 'VIEWER' AND (p.code LIKE '%\_VIEW' AND p.code <> 'AUDIT_LOG_VIEW'))
    OR (r.code = 'AUDITOR' AND (p.code LIKE '%\_VIEW' OR p.code = 'AUDIT_MANAGE'))
    OR (r.code = 'PLANT_MANAGER' AND (p.code LIKE '%\_VIEW' OR p.code IN (
            'FACILITY_MANAGE', 'INSPECTION_APPROVE', 'DISPOSITION_RECORD', 'NCR_ASSIGN', 'NCR_CLOSE',
            'CAPA_APPROVE')))
    OR (r.code = 'QUALITY_MANAGER' AND (p.code LIKE '%\_VIEW' OR p.code IN (
            'SUPPLIER_APPROVE', 'TEMPLATE_MANAGE', 'TEMPLATE_PUBLISH', 'INSPECTION_CREATE', 'INSPECTION_EXECUTE',
            'INSPECTION_APPROVE', 'DISPOSITION_RECORD', 'NCR_CREATE', 'NCR_ASSIGN', 'NCR_CLOSE', 'CAPA_MANAGE',
            'CAPA_APPROVE', 'TRACEABILITY_MANAGE', 'CALIBRATION_MANAGE', 'AUDIT_MANAGE')))
    OR (r.code = 'QUALITY_ENGINEER' AND (p.code IN ('SUPPLIER_VIEW', 'PRODUCT_VIEW', 'PURCHASE_VIEW', 'FACILITY_VIEW',
            'TEMPLATE_VIEW', 'INSPECTION_VIEW', 'NCR_VIEW', 'CAPA_VIEW', 'TRACEABILITY_VIEW', 'CALIBRATION_VIEW',
            'REPORT_VIEW', 'PRODUCT_MANAGE', 'TEMPLATE_MANAGE', 'INSPECTION_CREATE', 'INSPECTION_EXECUTE',
            'NCR_CREATE', 'NCR_ASSIGN', 'CAPA_MANAGE', 'TRACEABILITY_MANAGE', 'CALIBRATION_MANAGE')))
    OR (r.code = 'QUALITY_INSPECTOR' AND p.code IN ('SUPPLIER_VIEW', 'PRODUCT_VIEW', 'PURCHASE_VIEW', 'FACILITY_VIEW',
            'TEMPLATE_VIEW', 'INSPECTION_VIEW', 'INSPECTION_CREATE', 'INSPECTION_EXECUTE', 'NCR_VIEW', 'NCR_CREATE',
            'CALIBRATION_VIEW', 'TRACEABILITY_VIEW', 'RECEIPT_MANAGE'))
    OR (r.code = 'SUPPLIER_MANAGER' AND p.code IN ('FACILITY_VIEW', 'SUPPLIER_VIEW', 'SUPPLIER_CREATE',
            'SUPPLIER_UPDATE', 'SUPPLIER_APPROVE', 'PRODUCT_VIEW', 'PURCHASE_VIEW', 'PURCHASE_MANAGE',
            'RECEIPT_MANAGE', 'INSPECTION_VIEW', 'NCR_VIEW', 'CAPA_VIEW', 'REPORT_VIEW'));

-- Row-level security. FORCE makes the policies apply to the table owner too.
ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenants FORCE ROW LEVEL SECURITY;
CREATE POLICY tenants_read ON tenants FOR SELECT
    USING (id = current_tenant_id()
        OR id IN (SELECT tenant_id FROM tenant_memberships WHERE user_id = current_app_user_id()));
CREATE POLICY tenants_insert ON tenants FOR INSERT WITH CHECK (id = current_tenant_id());
CREATE POLICY tenants_update ON tenants FOR UPDATE
    USING (id = current_tenant_id()) WITH CHECK (id = current_tenant_id());

ALTER TABLE tenant_memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_memberships FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_memberships
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY memberships_own_read ON tenant_memberships FOR SELECT
    USING (user_id = current_app_user_id());

ALTER TABLE roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE roles FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON roles
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());
CREATE POLICY roles_system_read ON roles FOR SELECT USING (tenant_id IS NULL);

ALTER TABLE role_permissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE role_permissions FORCE ROW LEVEL SECURITY;
CREATE POLICY role_permissions_read ON role_permissions FOR SELECT
    USING (role_id IN (SELECT id FROM roles));
CREATE POLICY role_permissions_insert ON role_permissions FOR INSERT
    WITH CHECK (role_id IN (SELECT id FROM roles WHERE tenant_id = current_tenant_id()));
CREATE POLICY role_permissions_delete ON role_permissions FOR DELETE
    USING (role_id IN (SELECT id FROM roles WHERE tenant_id = current_tenant_id()));

ALTER TABLE user_roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_roles FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON user_roles
    USING (tenant_id = current_tenant_id()) WITH CHECK (tenant_id = current_tenant_id());

ALTER TABLE audit_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_logs FORCE ROW LEVEL SECURITY;
CREATE POLICY audit_logs_read ON audit_logs FOR SELECT USING (tenant_id = current_tenant_id());
CREATE POLICY audit_logs_insert ON audit_logs FOR INSERT
    WITH CHECK (tenant_id IS NULL OR tenant_id = current_tenant_id());
