-- Tenants, users, memberships, roles, tokens and the audit log.
-- ${approle} is the unprivileged role the application connects as.

SET LOCAL check_function_bodies = off;

-- User acting in the current transaction, NULL when none. Lets a user see their own memberships before
-- a tenant has been chosen.
CREATE FUNCTION current_app_user_id() RETURNS uuid
    LANGUAGE sql STABLE
    AS $$
SELECT NULLIF(current_setting('app.user_id', true), '')::uuid;
$$;

-- tenant_id is NULL for events with no tenant, such as a failed login.
CREATE TABLE audit_logs (
    id uuid NOT NULL,
    tenant_id uuid,
    user_id uuid,
    action text NOT NULL,
    entity_type text NOT NULL,
    entity_id text,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    ip_address text,
    correlation_id text,
    previous_state jsonb,
    new_state jsonb
);

ALTER TABLE ONLY audit_logs FORCE ROW LEVEL SECURITY;

CREATE TABLE permissions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    code text NOT NULL,
    module text NOT NULL,
    description text NOT NULL
);

-- Reached only through the token hash by the auth service, before any tenant is known: no RLS.
CREATE TABLE refresh_tokens (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    family_id uuid NOT NULL,
    token_hash text NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    consumed_at timestamp with time zone,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL
);

CREATE TABLE role_permissions (
    role_id uuid NOT NULL,
    permission_id uuid NOT NULL
);

ALTER TABLE ONLY role_permissions FORCE ROW LEVEL SECURITY;

-- tenant_id is NULL for the system defaults shared by all tenants, which tenants cannot change.
CREATE TABLE roles (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    tenant_id uuid,
    code text NOT NULL,
    name text NOT NULL,
    description text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL
);

ALTER TABLE ONLY roles FORCE ROW LEVEL SECURITY;

CREATE TABLE tenant_memberships (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    status text DEFAULT 'ACTIVE'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_tenant_memberships_status CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'INACTIVE'::text])))
);

ALTER TABLE ONLY tenant_memberships FORCE ROW LEVEL SECURITY;

CREATE TABLE tenants (
    id uuid NOT NULL,
    name text NOT NULL,
    legal_name text,
    gstin text,
    industry text,
    country_code text DEFAULT 'IN'::text NOT NULL,
    timezone text DEFAULT 'Asia/Kolkata'::text NOT NULL,
    currency text DEFAULT 'INR'::text NOT NULL,
    locale text DEFAULT 'en-IN'::text NOT NULL,
    address text,
    onboarded_at timestamp with time zone,
    status text DEFAULT 'ACTIVE'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    suspended_at timestamp with time zone,
    suspension_reason text,
    CONSTRAINT ck_tenants_gstin CHECK (((gstin IS NULL) OR (gstin ~ '^[0-9]{2}[A-Z0-9]{13}$'::text))),
    CONSTRAINT ck_tenants_status CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'SUSPENDED'::text])))
);

ALTER TABLE ONLY tenants FORCE ROW LEVEL SECURITY;

CREATE TABLE user_roles (
    id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    membership_id uuid NOT NULL,
    role_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL
);

ALTER TABLE ONLY user_roles FORCE ROW LEVEL SECURITY;

-- Emails are normalised by the application; the CHECK guarantees uniqueness is case-insensitive.
CREATE TABLE users (
    id uuid NOT NULL,
    email text NOT NULL,
    password_hash text NOT NULL,
    full_name text NOT NULL,
    status text DEFAULT 'ACTIVE'::text NOT NULL,
    failed_login_count integer DEFAULT 0 NOT NULL,
    locked_until timestamp with time zone,
    last_login_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by uuid,
    updated_by uuid,
    version bigint DEFAULT 0 NOT NULL,
    email_verified_at timestamp with time zone,
    preferred_language text,
    CONSTRAINT ck_users_email_lowercase CHECK ((email = lower(email))),
    CONSTRAINT ck_users_status CHECK ((status = ANY (ARRAY['PENDING_VERIFICATION'::text, 'ACTIVE'::text, 'DISABLED'::text])))
);

-- Starting rows, loaded before row-level security is switched on.
INSERT INTO permissions VALUES ('a1178527-0b3a-4d7f-895c-a8a23d1022be', 'TENANT_MANAGE', 'Organisation', 'Change organisation profile and settings');
INSERT INTO permissions VALUES ('3358e2d9-0684-43b6-aa61-4a0c240ab69e', 'FACILITY_VIEW', 'Organisation', 'View facilities and departments');
INSERT INTO permissions VALUES ('056320cd-d249-41fa-9528-9d045c5964b5', 'FACILITY_MANAGE', 'Organisation', 'Manage facilities, departments, lines and shifts');
INSERT INTO permissions VALUES ('43a70ff6-9a50-4e07-8fc3-0fa561c5aa4c', 'USER_MANAGE', 'Users', 'Add and deactivate users, assign roles');
INSERT INTO permissions VALUES ('98c951cf-accd-4a9b-95fb-847a62cf492e', 'ROLE_MANAGE', 'Users', 'Create and change custom roles');
INSERT INTO permissions VALUES ('992f2f81-0ca7-4ddb-b1f0-9e65973cf745', 'AUDIT_LOG_VIEW', 'Users', 'View the audit trail');
INSERT INTO permissions VALUES ('4db0e065-0eb9-4ddb-8996-ff1483c46311', 'SUPPLIER_VIEW', 'Suppliers', 'View suppliers');
INSERT INTO permissions VALUES ('2d616a48-bf00-4112-841f-89903386da2a', 'SUPPLIER_CREATE', 'Suppliers', 'Register suppliers');
INSERT INTO permissions VALUES ('974c6c20-7753-41aa-81af-b67f3f668cb9', 'SUPPLIER_UPDATE', 'Suppliers', 'Change suppliers');
INSERT INTO permissions VALUES ('fa6be522-a3d9-449f-89d5-9b77c15bcd43', 'SUPPLIER_APPROVE', 'Suppliers', 'Approve or block suppliers');
INSERT INTO permissions VALUES ('c8259350-3e34-47f7-8f6f-d4cc83e8db7d', 'PRODUCT_VIEW', 'Products', 'View products and materials');
INSERT INTO permissions VALUES ('1b8fd7df-6a80-4af3-99b2-816f50d0bb0a', 'PRODUCT_MANAGE', 'Products', 'Manage products, materials and specifications');
INSERT INTO permissions VALUES ('050c4c6a-162d-4dd3-bb4b-a48463749528', 'PURCHASE_VIEW', 'Purchasing', 'View purchase orders and receipts');
INSERT INTO permissions VALUES ('b6385373-71c7-4a79-9ff3-294774df9b7e', 'PURCHASE_MANAGE', 'Purchasing', 'Manage purchase orders');
INSERT INTO permissions VALUES ('1c613762-54c7-404d-95dc-d1924b3e2517', 'RECEIPT_MANAGE', 'Purchasing', 'Register material receipts');
INSERT INTO permissions VALUES ('7bf20c29-55c2-43f1-be66-c504d2c83a8f', 'TEMPLATE_VIEW', 'Inspection', 'View inspection templates');
INSERT INTO permissions VALUES ('2e77c034-5617-481c-b4bc-2f42dff79af2', 'TEMPLATE_MANAGE', 'Inspection', 'Create and change draft templates');
INSERT INTO permissions VALUES ('8327a88d-abaf-43a1-b548-9c3fd895453e', 'TEMPLATE_PUBLISH', 'Inspection', 'Approve and publish templates');
INSERT INTO permissions VALUES ('c091b4d2-e25a-468a-8105-d51af1c1cb57', 'INSPECTION_VIEW', 'Inspection', 'View inspections');
INSERT INTO permissions VALUES ('bb658e8f-88b5-4e3a-939b-6b182ca4322b', 'INSPECTION_CREATE', 'Inspection', 'Create inspections');
INSERT INTO permissions VALUES ('07ace72b-259a-4da5-bebc-f8ac267744fe', 'INSPECTION_EXECUTE', 'Inspection', 'Record inspection results');
INSERT INTO permissions VALUES ('9e1fef44-7649-4bc4-8011-dd83b594558c', 'INSPECTION_APPROVE', 'Inspection', 'Approve or reject inspections');
INSERT INTO permissions VALUES ('d13eb94a-1543-47a8-885b-bea7e9322c3c', 'DISPOSITION_RECORD', 'Inspection', 'Record material disposition');
INSERT INTO permissions VALUES ('5881d964-4a76-43a9-942b-c87b4ee2ba9c', 'NCR_VIEW', 'Quality', 'View non-conformances');
INSERT INTO permissions VALUES ('6b7371c3-0604-425a-b67a-90fd61676326', 'NCR_CREATE', 'Quality', 'Raise non-conformances');
INSERT INTO permissions VALUES ('22ef7261-908c-4f52-b3a7-c2191c6ba1e1', 'NCR_ASSIGN', 'Quality', 'Assign and progress non-conformances');
INSERT INTO permissions VALUES ('21c8c79f-b7f2-42bc-aafe-c5bfe7f79597', 'NCR_CLOSE', 'Quality', 'Approve and close non-conformances');
INSERT INTO permissions VALUES ('45ec133d-56ea-4062-b4d6-4f3205a6fc8e', 'CAPA_VIEW', 'Quality', 'View corrective actions');
INSERT INTO permissions VALUES ('01703314-dcd6-48d0-b2dc-9e65a5527a90', 'CAPA_MANAGE', 'Quality', 'Create and progress corrective actions');
INSERT INTO permissions VALUES ('706f23e7-a074-439b-9261-d08438915caf', 'CAPA_APPROVE', 'Quality', 'Verify and close corrective actions');
INSERT INTO permissions VALUES ('1d59ffd9-15b9-4ff0-a9ff-90088f459d16', 'TRACEABILITY_VIEW', 'Traceability', 'View batch traceability');
INSERT INTO permissions VALUES ('08f9a8b0-a75d-4c6e-b795-81203867bdc5', 'TRACEABILITY_MANAGE', 'Traceability', 'Register batches and dispatches');
INSERT INTO permissions VALUES ('959bc0c1-48d0-48fd-ad97-0332319df876', 'CALIBRATION_VIEW', 'Calibration', 'View instruments');
INSERT INTO permissions VALUES ('adfeea65-a4bb-48e5-9372-a9ed474613fb', 'CALIBRATION_MANAGE', 'Calibration', 'Manage instruments and calibrations');
INSERT INTO permissions VALUES ('4cb4a270-d3fd-4b38-ac59-a27e0116ada2', 'AUDIT_VIEW', 'Audit', 'View quality audits');
INSERT INTO permissions VALUES ('a87a01c7-74ca-446e-9c2f-50616c98ebd1', 'AUDIT_MANAGE', 'Audit', 'Plan and run quality audits');
INSERT INTO permissions VALUES ('99ff0453-8336-4f05-be75-146a9ef31a30', 'REPORT_VIEW', 'Reporting', 'View analytics and reports');
INSERT INTO permissions VALUES ('4cdadef8-bbad-4983-9f24-37c24e79fa41', 'SUBSCRIPTION_MANAGE', 'Subscription', 'Manage the subscription');
INSERT INTO permissions VALUES ('3e0d84e8-2d77-484c-83bf-b74fa0b74b56', 'MATERIAL_CORRECTION_REQUEST', 'Traceability', 'Request corrections to material records');
INSERT INTO permissions VALUES ('3d66bff8-b9c0-40b2-a383-ea43e52b504b', 'MATERIAL_CORRECTION_APPROVE', 'Traceability', 'Approve corrections to material records');
INSERT INTO permissions VALUES ('1ef49635-59ce-458c-9f43-3938d6b4691d', 'RECALL_VIEW', 'Recall', 'View recalls and their customer contacts');
INSERT INTO permissions VALUES ('8d04b70d-80d6-46b2-a629-182b95691f74', 'RECALL_MANAGE', 'Recall', 'Start recalls and record customer contact');
INSERT INTO permissions VALUES ('8873e18a-2264-4e3e-bf2a-6819756bf813', 'RECALL_CLOSE', 'Recall', 'Approve exceptions and close recalls');
INSERT INTO permissions VALUES ('73759a43-6d08-4d77-9b08-9931c13806c4', 'AUDIT_CLOSE', 'Audit', 'Approve the closure of quality audits');
INSERT INTO roles VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', NULL, 'ORGANISATION_OWNER', 'Organisation Owner', 'Full control of the organisation', '2026-10-31 22:37:39.443513+00', '2026-10-31 22:37:39.443513+00', NULL, NULL, 0);
INSERT INTO roles VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', NULL, 'ORGANISATION_ADMIN', 'Organisation Admin', 'Administers the organisation and its users', '2026-10-31 22:37:39.443513+00', '2026-10-31 22:37:39.443513+00', NULL, NULL, 0);
INSERT INTO roles VALUES ('05ccaf28-c04e-45e9-ae05-2491fa06b8a6', NULL, 'PLANT_MANAGER', 'Plant Manager', 'Oversees plant quality operations', '2026-10-31 22:37:39.443513+00', '2026-10-31 22:37:39.443513+00', NULL, NULL, 0);
INSERT INTO roles VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', NULL, 'QUALITY_MANAGER', 'Quality Manager', 'Owns quality processes and approvals', '2026-10-31 22:37:39.443513+00', '2026-10-31 22:37:39.443513+00', NULL, NULL, 0);
INSERT INTO roles VALUES ('03f1668b-c5b1-4dda-a038-5fbb0898ee0a', NULL, 'QUALITY_ENGINEER', 'Quality Engineer', 'Configures inspections and investigates issues', '2026-10-31 22:37:39.443513+00', '2026-10-31 22:37:39.443513+00', NULL, NULL, 0);
INSERT INTO roles VALUES ('5b9f04fd-ca42-4225-84c0-9fc860bc3ce8', NULL, 'QUALITY_INSPECTOR', 'Quality Inspector', 'Performs inspections', '2026-10-31 22:37:39.443513+00', '2026-10-31 22:37:39.443513+00', NULL, NULL, 0);
INSERT INTO roles VALUES ('27e84678-2940-4c04-9791-aa05da9c2637', NULL, 'SUPPLIER_MANAGER', 'Supplier Manager', 'Manages suppliers and purchasing', '2026-10-31 22:37:39.443513+00', '2026-10-31 22:37:39.443513+00', NULL, NULL, 0);
INSERT INTO roles VALUES ('81074ba2-e8a0-4581-a6cd-acda0c29bbf1', NULL, 'AUDITOR', 'Auditor', 'Read access plus quality audits', '2026-10-31 22:37:39.443513+00', '2026-10-31 22:37:39.443513+00', NULL, NULL, 0);
INSERT INTO roles VALUES ('387b30e7-c72a-47b5-988d-aca4ac7a2767', NULL, 'VIEWER', 'Viewer', 'Read-only access', '2026-10-31 22:37:39.443513+00', '2026-10-31 22:37:39.443513+00', NULL, NULL, 0);
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', 'a1178527-0b3a-4d7f-895c-a8a23d1022be');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', 'a1178527-0b3a-4d7f-895c-a8a23d1022be');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '3358e2d9-0684-43b6-aa61-4a0c240ab69e');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '3358e2d9-0684-43b6-aa61-4a0c240ab69e');
INSERT INTO role_permissions VALUES ('05ccaf28-c04e-45e9-ae05-2491fa06b8a6', '3358e2d9-0684-43b6-aa61-4a0c240ab69e');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', '3358e2d9-0684-43b6-aa61-4a0c240ab69e');
INSERT INTO role_permissions VALUES ('03f1668b-c5b1-4dda-a038-5fbb0898ee0a', '3358e2d9-0684-43b6-aa61-4a0c240ab69e');
INSERT INTO role_permissions VALUES ('5b9f04fd-ca42-4225-84c0-9fc860bc3ce8', '3358e2d9-0684-43b6-aa61-4a0c240ab69e');
INSERT INTO role_permissions VALUES ('27e84678-2940-4c04-9791-aa05da9c2637', '3358e2d9-0684-43b6-aa61-4a0c240ab69e');
INSERT INTO role_permissions VALUES ('81074ba2-e8a0-4581-a6cd-acda0c29bbf1', '3358e2d9-0684-43b6-aa61-4a0c240ab69e');
INSERT INTO role_permissions VALUES ('387b30e7-c72a-47b5-988d-aca4ac7a2767', '3358e2d9-0684-43b6-aa61-4a0c240ab69e');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '056320cd-d249-41fa-9528-9d045c5964b5');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '056320cd-d249-41fa-9528-9d045c5964b5');
INSERT INTO role_permissions VALUES ('05ccaf28-c04e-45e9-ae05-2491fa06b8a6', '056320cd-d249-41fa-9528-9d045c5964b5');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '43a70ff6-9a50-4e07-8fc3-0fa561c5aa4c');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '43a70ff6-9a50-4e07-8fc3-0fa561c5aa4c');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '98c951cf-accd-4a9b-95fb-847a62cf492e');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '98c951cf-accd-4a9b-95fb-847a62cf492e');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '992f2f81-0ca7-4ddb-b1f0-9e65973cf745');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '992f2f81-0ca7-4ddb-b1f0-9e65973cf745');
INSERT INTO role_permissions VALUES ('05ccaf28-c04e-45e9-ae05-2491fa06b8a6', '992f2f81-0ca7-4ddb-b1f0-9e65973cf745');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', '992f2f81-0ca7-4ddb-b1f0-9e65973cf745');
INSERT INTO role_permissions VALUES ('81074ba2-e8a0-4581-a6cd-acda0c29bbf1', '992f2f81-0ca7-4ddb-b1f0-9e65973cf745');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '4db0e065-0eb9-4ddb-8996-ff1483c46311');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '4db0e065-0eb9-4ddb-8996-ff1483c46311');
INSERT INTO role_permissions VALUES ('05ccaf28-c04e-45e9-ae05-2491fa06b8a6', '4db0e065-0eb9-4ddb-8996-ff1483c46311');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', '4db0e065-0eb9-4ddb-8996-ff1483c46311');
INSERT INTO role_permissions VALUES ('03f1668b-c5b1-4dda-a038-5fbb0898ee0a', '4db0e065-0eb9-4ddb-8996-ff1483c46311');
INSERT INTO role_permissions VALUES ('5b9f04fd-ca42-4225-84c0-9fc860bc3ce8', '4db0e065-0eb9-4ddb-8996-ff1483c46311');
INSERT INTO role_permissions VALUES ('27e84678-2940-4c04-9791-aa05da9c2637', '4db0e065-0eb9-4ddb-8996-ff1483c46311');
INSERT INTO role_permissions VALUES ('81074ba2-e8a0-4581-a6cd-acda0c29bbf1', '4db0e065-0eb9-4ddb-8996-ff1483c46311');
INSERT INTO role_permissions VALUES ('387b30e7-c72a-47b5-988d-aca4ac7a2767', '4db0e065-0eb9-4ddb-8996-ff1483c46311');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '2d616a48-bf00-4112-841f-89903386da2a');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '2d616a48-bf00-4112-841f-89903386da2a');
INSERT INTO role_permissions VALUES ('27e84678-2940-4c04-9791-aa05da9c2637', '2d616a48-bf00-4112-841f-89903386da2a');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '974c6c20-7753-41aa-81af-b67f3f668cb9');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '974c6c20-7753-41aa-81af-b67f3f668cb9');
INSERT INTO role_permissions VALUES ('27e84678-2940-4c04-9791-aa05da9c2637', '974c6c20-7753-41aa-81af-b67f3f668cb9');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', 'fa6be522-a3d9-449f-89d5-9b77c15bcd43');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', 'fa6be522-a3d9-449f-89d5-9b77c15bcd43');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', 'fa6be522-a3d9-449f-89d5-9b77c15bcd43');
INSERT INTO role_permissions VALUES ('27e84678-2940-4c04-9791-aa05da9c2637', 'fa6be522-a3d9-449f-89d5-9b77c15bcd43');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', 'c8259350-3e34-47f7-8f6f-d4cc83e8db7d');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', 'c8259350-3e34-47f7-8f6f-d4cc83e8db7d');
INSERT INTO role_permissions VALUES ('05ccaf28-c04e-45e9-ae05-2491fa06b8a6', 'c8259350-3e34-47f7-8f6f-d4cc83e8db7d');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', 'c8259350-3e34-47f7-8f6f-d4cc83e8db7d');
INSERT INTO role_permissions VALUES ('03f1668b-c5b1-4dda-a038-5fbb0898ee0a', 'c8259350-3e34-47f7-8f6f-d4cc83e8db7d');
INSERT INTO role_permissions VALUES ('5b9f04fd-ca42-4225-84c0-9fc860bc3ce8', 'c8259350-3e34-47f7-8f6f-d4cc83e8db7d');
INSERT INTO role_permissions VALUES ('27e84678-2940-4c04-9791-aa05da9c2637', 'c8259350-3e34-47f7-8f6f-d4cc83e8db7d');
INSERT INTO role_permissions VALUES ('81074ba2-e8a0-4581-a6cd-acda0c29bbf1', 'c8259350-3e34-47f7-8f6f-d4cc83e8db7d');
INSERT INTO role_permissions VALUES ('387b30e7-c72a-47b5-988d-aca4ac7a2767', 'c8259350-3e34-47f7-8f6f-d4cc83e8db7d');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '1b8fd7df-6a80-4af3-99b2-816f50d0bb0a');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '1b8fd7df-6a80-4af3-99b2-816f50d0bb0a');
INSERT INTO role_permissions VALUES ('03f1668b-c5b1-4dda-a038-5fbb0898ee0a', '1b8fd7df-6a80-4af3-99b2-816f50d0bb0a');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '050c4c6a-162d-4dd3-bb4b-a48463749528');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '050c4c6a-162d-4dd3-bb4b-a48463749528');
INSERT INTO role_permissions VALUES ('05ccaf28-c04e-45e9-ae05-2491fa06b8a6', '050c4c6a-162d-4dd3-bb4b-a48463749528');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', '050c4c6a-162d-4dd3-bb4b-a48463749528');
INSERT INTO role_permissions VALUES ('03f1668b-c5b1-4dda-a038-5fbb0898ee0a', '050c4c6a-162d-4dd3-bb4b-a48463749528');
INSERT INTO role_permissions VALUES ('5b9f04fd-ca42-4225-84c0-9fc860bc3ce8', '050c4c6a-162d-4dd3-bb4b-a48463749528');
INSERT INTO role_permissions VALUES ('27e84678-2940-4c04-9791-aa05da9c2637', '050c4c6a-162d-4dd3-bb4b-a48463749528');
INSERT INTO role_permissions VALUES ('81074ba2-e8a0-4581-a6cd-acda0c29bbf1', '050c4c6a-162d-4dd3-bb4b-a48463749528');
INSERT INTO role_permissions VALUES ('387b30e7-c72a-47b5-988d-aca4ac7a2767', '050c4c6a-162d-4dd3-bb4b-a48463749528');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', 'b6385373-71c7-4a79-9ff3-294774df9b7e');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', 'b6385373-71c7-4a79-9ff3-294774df9b7e');
INSERT INTO role_permissions VALUES ('27e84678-2940-4c04-9791-aa05da9c2637', 'b6385373-71c7-4a79-9ff3-294774df9b7e');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '1c613762-54c7-404d-95dc-d1924b3e2517');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '1c613762-54c7-404d-95dc-d1924b3e2517');
INSERT INTO role_permissions VALUES ('5b9f04fd-ca42-4225-84c0-9fc860bc3ce8', '1c613762-54c7-404d-95dc-d1924b3e2517');
INSERT INTO role_permissions VALUES ('27e84678-2940-4c04-9791-aa05da9c2637', '1c613762-54c7-404d-95dc-d1924b3e2517');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '7bf20c29-55c2-43f1-be66-c504d2c83a8f');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '7bf20c29-55c2-43f1-be66-c504d2c83a8f');
INSERT INTO role_permissions VALUES ('05ccaf28-c04e-45e9-ae05-2491fa06b8a6', '7bf20c29-55c2-43f1-be66-c504d2c83a8f');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', '7bf20c29-55c2-43f1-be66-c504d2c83a8f');
INSERT INTO role_permissions VALUES ('03f1668b-c5b1-4dda-a038-5fbb0898ee0a', '7bf20c29-55c2-43f1-be66-c504d2c83a8f');
INSERT INTO role_permissions VALUES ('5b9f04fd-ca42-4225-84c0-9fc860bc3ce8', '7bf20c29-55c2-43f1-be66-c504d2c83a8f');
INSERT INTO role_permissions VALUES ('81074ba2-e8a0-4581-a6cd-acda0c29bbf1', '7bf20c29-55c2-43f1-be66-c504d2c83a8f');
INSERT INTO role_permissions VALUES ('387b30e7-c72a-47b5-988d-aca4ac7a2767', '7bf20c29-55c2-43f1-be66-c504d2c83a8f');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '2e77c034-5617-481c-b4bc-2f42dff79af2');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '2e77c034-5617-481c-b4bc-2f42dff79af2');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', '2e77c034-5617-481c-b4bc-2f42dff79af2');
INSERT INTO role_permissions VALUES ('03f1668b-c5b1-4dda-a038-5fbb0898ee0a', '2e77c034-5617-481c-b4bc-2f42dff79af2');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '8327a88d-abaf-43a1-b548-9c3fd895453e');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '8327a88d-abaf-43a1-b548-9c3fd895453e');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', '8327a88d-abaf-43a1-b548-9c3fd895453e');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', 'c091b4d2-e25a-468a-8105-d51af1c1cb57');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', 'c091b4d2-e25a-468a-8105-d51af1c1cb57');
INSERT INTO role_permissions VALUES ('05ccaf28-c04e-45e9-ae05-2491fa06b8a6', 'c091b4d2-e25a-468a-8105-d51af1c1cb57');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', 'c091b4d2-e25a-468a-8105-d51af1c1cb57');
INSERT INTO role_permissions VALUES ('03f1668b-c5b1-4dda-a038-5fbb0898ee0a', 'c091b4d2-e25a-468a-8105-d51af1c1cb57');
INSERT INTO role_permissions VALUES ('5b9f04fd-ca42-4225-84c0-9fc860bc3ce8', 'c091b4d2-e25a-468a-8105-d51af1c1cb57');
INSERT INTO role_permissions VALUES ('27e84678-2940-4c04-9791-aa05da9c2637', 'c091b4d2-e25a-468a-8105-d51af1c1cb57');
INSERT INTO role_permissions VALUES ('81074ba2-e8a0-4581-a6cd-acda0c29bbf1', 'c091b4d2-e25a-468a-8105-d51af1c1cb57');
INSERT INTO role_permissions VALUES ('387b30e7-c72a-47b5-988d-aca4ac7a2767', 'c091b4d2-e25a-468a-8105-d51af1c1cb57');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', 'bb658e8f-88b5-4e3a-939b-6b182ca4322b');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', 'bb658e8f-88b5-4e3a-939b-6b182ca4322b');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', 'bb658e8f-88b5-4e3a-939b-6b182ca4322b');
INSERT INTO role_permissions VALUES ('03f1668b-c5b1-4dda-a038-5fbb0898ee0a', 'bb658e8f-88b5-4e3a-939b-6b182ca4322b');
INSERT INTO role_permissions VALUES ('5b9f04fd-ca42-4225-84c0-9fc860bc3ce8', 'bb658e8f-88b5-4e3a-939b-6b182ca4322b');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '07ace72b-259a-4da5-bebc-f8ac267744fe');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '07ace72b-259a-4da5-bebc-f8ac267744fe');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', '07ace72b-259a-4da5-bebc-f8ac267744fe');
INSERT INTO role_permissions VALUES ('03f1668b-c5b1-4dda-a038-5fbb0898ee0a', '07ace72b-259a-4da5-bebc-f8ac267744fe');
INSERT INTO role_permissions VALUES ('5b9f04fd-ca42-4225-84c0-9fc860bc3ce8', '07ace72b-259a-4da5-bebc-f8ac267744fe');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '9e1fef44-7649-4bc4-8011-dd83b594558c');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '9e1fef44-7649-4bc4-8011-dd83b594558c');
INSERT INTO role_permissions VALUES ('05ccaf28-c04e-45e9-ae05-2491fa06b8a6', '9e1fef44-7649-4bc4-8011-dd83b594558c');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', '9e1fef44-7649-4bc4-8011-dd83b594558c');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', 'd13eb94a-1543-47a8-885b-bea7e9322c3c');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', 'd13eb94a-1543-47a8-885b-bea7e9322c3c');
INSERT INTO role_permissions VALUES ('05ccaf28-c04e-45e9-ae05-2491fa06b8a6', 'd13eb94a-1543-47a8-885b-bea7e9322c3c');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', 'd13eb94a-1543-47a8-885b-bea7e9322c3c');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '5881d964-4a76-43a9-942b-c87b4ee2ba9c');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '5881d964-4a76-43a9-942b-c87b4ee2ba9c');
INSERT INTO role_permissions VALUES ('05ccaf28-c04e-45e9-ae05-2491fa06b8a6', '5881d964-4a76-43a9-942b-c87b4ee2ba9c');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', '5881d964-4a76-43a9-942b-c87b4ee2ba9c');
INSERT INTO role_permissions VALUES ('03f1668b-c5b1-4dda-a038-5fbb0898ee0a', '5881d964-4a76-43a9-942b-c87b4ee2ba9c');
INSERT INTO role_permissions VALUES ('5b9f04fd-ca42-4225-84c0-9fc860bc3ce8', '5881d964-4a76-43a9-942b-c87b4ee2ba9c');
INSERT INTO role_permissions VALUES ('27e84678-2940-4c04-9791-aa05da9c2637', '5881d964-4a76-43a9-942b-c87b4ee2ba9c');
INSERT INTO role_permissions VALUES ('81074ba2-e8a0-4581-a6cd-acda0c29bbf1', '5881d964-4a76-43a9-942b-c87b4ee2ba9c');
INSERT INTO role_permissions VALUES ('387b30e7-c72a-47b5-988d-aca4ac7a2767', '5881d964-4a76-43a9-942b-c87b4ee2ba9c');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '6b7371c3-0604-425a-b67a-90fd61676326');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '6b7371c3-0604-425a-b67a-90fd61676326');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', '6b7371c3-0604-425a-b67a-90fd61676326');
INSERT INTO role_permissions VALUES ('03f1668b-c5b1-4dda-a038-5fbb0898ee0a', '6b7371c3-0604-425a-b67a-90fd61676326');
INSERT INTO role_permissions VALUES ('5b9f04fd-ca42-4225-84c0-9fc860bc3ce8', '6b7371c3-0604-425a-b67a-90fd61676326');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '22ef7261-908c-4f52-b3a7-c2191c6ba1e1');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '22ef7261-908c-4f52-b3a7-c2191c6ba1e1');
INSERT INTO role_permissions VALUES ('05ccaf28-c04e-45e9-ae05-2491fa06b8a6', '22ef7261-908c-4f52-b3a7-c2191c6ba1e1');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', '22ef7261-908c-4f52-b3a7-c2191c6ba1e1');
INSERT INTO role_permissions VALUES ('03f1668b-c5b1-4dda-a038-5fbb0898ee0a', '22ef7261-908c-4f52-b3a7-c2191c6ba1e1');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '21c8c79f-b7f2-42bc-aafe-c5bfe7f79597');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '21c8c79f-b7f2-42bc-aafe-c5bfe7f79597');
INSERT INTO role_permissions VALUES ('05ccaf28-c04e-45e9-ae05-2491fa06b8a6', '21c8c79f-b7f2-42bc-aafe-c5bfe7f79597');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', '21c8c79f-b7f2-42bc-aafe-c5bfe7f79597');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '45ec133d-56ea-4062-b4d6-4f3205a6fc8e');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '45ec133d-56ea-4062-b4d6-4f3205a6fc8e');
INSERT INTO role_permissions VALUES ('05ccaf28-c04e-45e9-ae05-2491fa06b8a6', '45ec133d-56ea-4062-b4d6-4f3205a6fc8e');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', '45ec133d-56ea-4062-b4d6-4f3205a6fc8e');
INSERT INTO role_permissions VALUES ('03f1668b-c5b1-4dda-a038-5fbb0898ee0a', '45ec133d-56ea-4062-b4d6-4f3205a6fc8e');
INSERT INTO role_permissions VALUES ('27e84678-2940-4c04-9791-aa05da9c2637', '45ec133d-56ea-4062-b4d6-4f3205a6fc8e');
INSERT INTO role_permissions VALUES ('81074ba2-e8a0-4581-a6cd-acda0c29bbf1', '45ec133d-56ea-4062-b4d6-4f3205a6fc8e');
INSERT INTO role_permissions VALUES ('387b30e7-c72a-47b5-988d-aca4ac7a2767', '45ec133d-56ea-4062-b4d6-4f3205a6fc8e');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '01703314-dcd6-48d0-b2dc-9e65a5527a90');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '01703314-dcd6-48d0-b2dc-9e65a5527a90');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', '01703314-dcd6-48d0-b2dc-9e65a5527a90');
INSERT INTO role_permissions VALUES ('03f1668b-c5b1-4dda-a038-5fbb0898ee0a', '01703314-dcd6-48d0-b2dc-9e65a5527a90');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '706f23e7-a074-439b-9261-d08438915caf');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '706f23e7-a074-439b-9261-d08438915caf');
INSERT INTO role_permissions VALUES ('05ccaf28-c04e-45e9-ae05-2491fa06b8a6', '706f23e7-a074-439b-9261-d08438915caf');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', '706f23e7-a074-439b-9261-d08438915caf');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '1d59ffd9-15b9-4ff0-a9ff-90088f459d16');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '1d59ffd9-15b9-4ff0-a9ff-90088f459d16');
INSERT INTO role_permissions VALUES ('05ccaf28-c04e-45e9-ae05-2491fa06b8a6', '1d59ffd9-15b9-4ff0-a9ff-90088f459d16');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', '1d59ffd9-15b9-4ff0-a9ff-90088f459d16');
INSERT INTO role_permissions VALUES ('03f1668b-c5b1-4dda-a038-5fbb0898ee0a', '1d59ffd9-15b9-4ff0-a9ff-90088f459d16');
INSERT INTO role_permissions VALUES ('5b9f04fd-ca42-4225-84c0-9fc860bc3ce8', '1d59ffd9-15b9-4ff0-a9ff-90088f459d16');
INSERT INTO role_permissions VALUES ('81074ba2-e8a0-4581-a6cd-acda0c29bbf1', '1d59ffd9-15b9-4ff0-a9ff-90088f459d16');
INSERT INTO role_permissions VALUES ('387b30e7-c72a-47b5-988d-aca4ac7a2767', '1d59ffd9-15b9-4ff0-a9ff-90088f459d16');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '08f9a8b0-a75d-4c6e-b795-81203867bdc5');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '08f9a8b0-a75d-4c6e-b795-81203867bdc5');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', '08f9a8b0-a75d-4c6e-b795-81203867bdc5');
INSERT INTO role_permissions VALUES ('03f1668b-c5b1-4dda-a038-5fbb0898ee0a', '08f9a8b0-a75d-4c6e-b795-81203867bdc5');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '959bc0c1-48d0-48fd-ad97-0332319df876');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '959bc0c1-48d0-48fd-ad97-0332319df876');
INSERT INTO role_permissions VALUES ('05ccaf28-c04e-45e9-ae05-2491fa06b8a6', '959bc0c1-48d0-48fd-ad97-0332319df876');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', '959bc0c1-48d0-48fd-ad97-0332319df876');
INSERT INTO role_permissions VALUES ('03f1668b-c5b1-4dda-a038-5fbb0898ee0a', '959bc0c1-48d0-48fd-ad97-0332319df876');
INSERT INTO role_permissions VALUES ('5b9f04fd-ca42-4225-84c0-9fc860bc3ce8', '959bc0c1-48d0-48fd-ad97-0332319df876');
INSERT INTO role_permissions VALUES ('81074ba2-e8a0-4581-a6cd-acda0c29bbf1', '959bc0c1-48d0-48fd-ad97-0332319df876');
INSERT INTO role_permissions VALUES ('387b30e7-c72a-47b5-988d-aca4ac7a2767', '959bc0c1-48d0-48fd-ad97-0332319df876');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', 'adfeea65-a4bb-48e5-9372-a9ed474613fb');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', 'adfeea65-a4bb-48e5-9372-a9ed474613fb');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', 'adfeea65-a4bb-48e5-9372-a9ed474613fb');
INSERT INTO role_permissions VALUES ('03f1668b-c5b1-4dda-a038-5fbb0898ee0a', 'adfeea65-a4bb-48e5-9372-a9ed474613fb');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '4cb4a270-d3fd-4b38-ac59-a27e0116ada2');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '4cb4a270-d3fd-4b38-ac59-a27e0116ada2');
INSERT INTO role_permissions VALUES ('05ccaf28-c04e-45e9-ae05-2491fa06b8a6', '4cb4a270-d3fd-4b38-ac59-a27e0116ada2');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', '4cb4a270-d3fd-4b38-ac59-a27e0116ada2');
INSERT INTO role_permissions VALUES ('81074ba2-e8a0-4581-a6cd-acda0c29bbf1', '4cb4a270-d3fd-4b38-ac59-a27e0116ada2');
INSERT INTO role_permissions VALUES ('387b30e7-c72a-47b5-988d-aca4ac7a2767', '4cb4a270-d3fd-4b38-ac59-a27e0116ada2');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', 'a87a01c7-74ca-446e-9c2f-50616c98ebd1');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', 'a87a01c7-74ca-446e-9c2f-50616c98ebd1');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', 'a87a01c7-74ca-446e-9c2f-50616c98ebd1');
INSERT INTO role_permissions VALUES ('81074ba2-e8a0-4581-a6cd-acda0c29bbf1', 'a87a01c7-74ca-446e-9c2f-50616c98ebd1');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '99ff0453-8336-4f05-be75-146a9ef31a30');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '99ff0453-8336-4f05-be75-146a9ef31a30');
INSERT INTO role_permissions VALUES ('05ccaf28-c04e-45e9-ae05-2491fa06b8a6', '99ff0453-8336-4f05-be75-146a9ef31a30');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', '99ff0453-8336-4f05-be75-146a9ef31a30');
INSERT INTO role_permissions VALUES ('03f1668b-c5b1-4dda-a038-5fbb0898ee0a', '99ff0453-8336-4f05-be75-146a9ef31a30');
INSERT INTO role_permissions VALUES ('27e84678-2940-4c04-9791-aa05da9c2637', '99ff0453-8336-4f05-be75-146a9ef31a30');
INSERT INTO role_permissions VALUES ('81074ba2-e8a0-4581-a6cd-acda0c29bbf1', '99ff0453-8336-4f05-be75-146a9ef31a30');
INSERT INTO role_permissions VALUES ('387b30e7-c72a-47b5-988d-aca4ac7a2767', '99ff0453-8336-4f05-be75-146a9ef31a30');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '4cdadef8-bbad-4983-9f24-37c24e79fa41');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '3e0d84e8-2d77-484c-83bf-b74fa0b74b56');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '3d66bff8-b9c0-40b2-a383-ea43e52b504b');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '3e0d84e8-2d77-484c-83bf-b74fa0b74b56');
INSERT INTO role_permissions VALUES ('05ccaf28-c04e-45e9-ae05-2491fa06b8a6', '3e0d84e8-2d77-484c-83bf-b74fa0b74b56');
INSERT INTO role_permissions VALUES ('05ccaf28-c04e-45e9-ae05-2491fa06b8a6', '3d66bff8-b9c0-40b2-a383-ea43e52b504b');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', '3e0d84e8-2d77-484c-83bf-b74fa0b74b56');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', '3d66bff8-b9c0-40b2-a383-ea43e52b504b');
INSERT INTO role_permissions VALUES ('03f1668b-c5b1-4dda-a038-5fbb0898ee0a', '3e0d84e8-2d77-484c-83bf-b74fa0b74b56');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '1ef49635-59ce-458c-9f43-3938d6b4691d');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '8d04b70d-80d6-46b2-a629-182b95691f74');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '8873e18a-2264-4e3e-bf2a-6819756bf813');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '1ef49635-59ce-458c-9f43-3938d6b4691d');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '8d04b70d-80d6-46b2-a629-182b95691f74');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '8873e18a-2264-4e3e-bf2a-6819756bf813');
INSERT INTO role_permissions VALUES ('05ccaf28-c04e-45e9-ae05-2491fa06b8a6', '1ef49635-59ce-458c-9f43-3938d6b4691d');
INSERT INTO role_permissions VALUES ('05ccaf28-c04e-45e9-ae05-2491fa06b8a6', '8873e18a-2264-4e3e-bf2a-6819756bf813');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', '1ef49635-59ce-458c-9f43-3938d6b4691d');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', '8d04b70d-80d6-46b2-a629-182b95691f74');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', '8873e18a-2264-4e3e-bf2a-6819756bf813');
INSERT INTO role_permissions VALUES ('03f1668b-c5b1-4dda-a038-5fbb0898ee0a', '1ef49635-59ce-458c-9f43-3938d6b4691d');
INSERT INTO role_permissions VALUES ('03f1668b-c5b1-4dda-a038-5fbb0898ee0a', '8d04b70d-80d6-46b2-a629-182b95691f74');
INSERT INTO role_permissions VALUES ('81074ba2-e8a0-4581-a6cd-acda0c29bbf1', '1ef49635-59ce-458c-9f43-3938d6b4691d');
INSERT INTO role_permissions VALUES ('387b30e7-c72a-47b5-988d-aca4ac7a2767', '1ef49635-59ce-458c-9f43-3938d6b4691d');
INSERT INTO role_permissions VALUES ('02cd2220-5ddf-4655-83cc-cf89becb9150', '73759a43-6d08-4d77-9b08-9931c13806c4');
INSERT INTO role_permissions VALUES ('e00c2777-4af4-47d7-b08d-2da534c64e3f', '73759a43-6d08-4d77-9b08-9931c13806c4');
INSERT INTO role_permissions VALUES ('05ccaf28-c04e-45e9-ae05-2491fa06b8a6', '73759a43-6d08-4d77-9b08-9931c13806c4');
INSERT INTO role_permissions VALUES ('1ef0a29f-e6ff-4fe5-a71c-2fd2e000d9ad', '73759a43-6d08-4d77-9b08-9931c13806c4');

ALTER TABLE ONLY audit_logs
    ADD CONSTRAINT audit_logs_pkey PRIMARY KEY (id);

ALTER TABLE ONLY permissions
    ADD CONSTRAINT permissions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY refresh_tokens
    ADD CONSTRAINT refresh_tokens_pkey PRIMARY KEY (id);

ALTER TABLE ONLY role_permissions
    ADD CONSTRAINT role_permissions_pkey PRIMARY KEY (role_id, permission_id);

ALTER TABLE ONLY roles
    ADD CONSTRAINT roles_pkey PRIMARY KEY (id);

ALTER TABLE ONLY tenant_memberships
    ADD CONSTRAINT tenant_memberships_pkey PRIMARY KEY (id);

ALTER TABLE ONLY tenants
    ADD CONSTRAINT tenants_pkey PRIMARY KEY (id);

ALTER TABLE ONLY permissions
    ADD CONSTRAINT uq_permissions_code UNIQUE (code);

ALTER TABLE ONLY refresh_tokens
    ADD CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash);

ALTER TABLE ONLY tenant_memberships
    ADD CONSTRAINT uq_tenant_memberships_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE ONLY tenant_memberships
    ADD CONSTRAINT uq_tenant_memberships_tenant_user UNIQUE (tenant_id, user_id);

ALTER TABLE ONLY user_roles
    ADD CONSTRAINT uq_user_roles_membership_role UNIQUE (membership_id, role_id);

ALTER TABLE ONLY users
    ADD CONSTRAINT uq_users_email UNIQUE (email);

ALTER TABLE ONLY user_roles
    ADD CONSTRAINT user_roles_pkey PRIMARY KEY (id);

ALTER TABLE ONLY users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);

CREATE INDEX ix_audit_logs_tenant_entity ON audit_logs USING btree (tenant_id, entity_type, entity_id);

CREATE INDEX ix_audit_logs_tenant_time ON audit_logs USING btree (tenant_id, occurred_at DESC);

CREATE INDEX ix_audit_logs_time ON audit_logs USING btree (occurred_at DESC);

CREATE INDEX ix_refresh_tokens_expiry ON refresh_tokens USING btree (expires_at);

CREATE INDEX ix_refresh_tokens_family ON refresh_tokens USING btree (family_id);

CREATE INDEX ix_refresh_tokens_user ON refresh_tokens USING btree (user_id);

CREATE INDEX ix_role_permissions_permission ON role_permissions USING btree (permission_id);

CREATE INDEX ix_tenant_memberships_user ON tenant_memberships USING btree (user_id);

CREATE INDEX ix_user_roles_tenant_role ON user_roles USING btree (tenant_id, role_id);

CREATE UNIQUE INDEX uq_roles_system_code ON roles USING btree (code) WHERE (tenant_id IS NULL);

CREATE UNIQUE INDEX uq_roles_tenant_code ON roles USING btree (tenant_id, code) WHERE (tenant_id IS NOT NULL);

CREATE TRIGGER trg_refresh_tokens_updated_at BEFORE UPDATE ON refresh_tokens FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_roles_updated_at BEFORE UPDATE ON roles FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_tenant_memberships_updated_at BEFORE UPDATE ON tenant_memberships FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_tenants_updated_at BEFORE UPDATE ON tenants FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_users_updated_at BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE ONLY audit_logs
    ADD CONSTRAINT audit_logs_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY user_roles
    ADD CONSTRAINT fk_user_roles_membership FOREIGN KEY (tenant_id, membership_id) REFERENCES tenant_memberships(tenant_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY refresh_tokens
    ADD CONSTRAINT refresh_tokens_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY refresh_tokens
    ADD CONSTRAINT refresh_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id);

ALTER TABLE ONLY role_permissions
    ADD CONSTRAINT role_permissions_permission_id_fkey FOREIGN KEY (permission_id) REFERENCES permissions(id);

ALTER TABLE ONLY role_permissions
    ADD CONSTRAINT role_permissions_role_id_fkey FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE;

ALTER TABLE ONLY roles
    ADD CONSTRAINT roles_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY tenant_memberships
    ADD CONSTRAINT tenant_memberships_tenant_id_fkey FOREIGN KEY (tenant_id) REFERENCES tenants(id);

ALTER TABLE ONLY tenant_memberships
    ADD CONSTRAINT tenant_memberships_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id);

ALTER TABLE ONLY user_roles
    ADD CONSTRAINT user_roles_role_id_fkey FOREIGN KEY (role_id) REFERENCES roles(id);

CREATE POLICY account_expiry ON tenant_memberships FOR SELECT TO "${flyway:user}" USING (true);

CREATE POLICY account_expiry ON tenants FOR UPDATE TO "${flyway:user}" USING (true) WITH CHECK (true);

CREATE POLICY account_expiry_read ON tenants FOR SELECT TO "${flyway:user}" USING (true);

ALTER TABLE audit_logs ENABLE ROW LEVEL SECURITY;

CREATE POLICY audit_logs_insert ON audit_logs FOR INSERT WITH CHECK (((tenant_id IS NULL) OR (tenant_id = current_tenant_id())));

CREATE POLICY audit_logs_read ON audit_logs FOR SELECT USING ((tenant_id = current_tenant_id()));

CREATE POLICY memberships_own_read ON tenant_memberships FOR SELECT USING ((user_id = current_app_user_id()));

CREATE POLICY platform_scan ON audit_logs FOR SELECT TO "${flyway:user}" USING (true);

ALTER TABLE role_permissions ENABLE ROW LEVEL SECURITY;

CREATE POLICY role_permissions_delete ON role_permissions FOR DELETE USING ((role_id IN ( SELECT roles.id
   FROM roles
  WHERE (roles.tenant_id = current_tenant_id()))));

CREATE POLICY role_permissions_insert ON role_permissions FOR INSERT WITH CHECK ((role_id IN ( SELECT roles.id
   FROM roles
  WHERE (roles.tenant_id = current_tenant_id()))));

CREATE POLICY role_permissions_read ON role_permissions FOR SELECT USING ((role_id IN ( SELECT roles.id
   FROM roles)));

ALTER TABLE roles ENABLE ROW LEVEL SECURITY;

CREATE POLICY roles_system_read ON roles FOR SELECT USING ((tenant_id IS NULL));

CREATE POLICY tenant_isolation ON roles USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON tenant_memberships USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

CREATE POLICY tenant_isolation ON user_roles USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));

ALTER TABLE tenant_memberships ENABLE ROW LEVEL SECURITY;

ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenants_insert ON tenants FOR INSERT WITH CHECK ((id = current_tenant_id()));

CREATE POLICY tenants_read ON tenants FOR SELECT USING (((id = current_tenant_id()) OR (id IN ( SELECT tenant_memberships.tenant_id
   FROM tenant_memberships
  WHERE (tenant_memberships.user_id = current_app_user_id())))));

CREATE POLICY tenants_update ON tenants FOR UPDATE USING ((id = current_tenant_id())) WITH CHECK ((id = current_tenant_id()));

ALTER TABLE user_roles ENABLE ROW LEVEL SECURITY;

GRANT SELECT,INSERT ON TABLE audit_logs TO ${approle};

GRANT SELECT ON TABLE permissions TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE refresh_tokens TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE role_permissions TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE roles TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE tenant_memberships TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE tenants TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE user_roles TO ${approle};

GRANT SELECT,INSERT,DELETE,UPDATE ON TABLE users TO ${approle};
