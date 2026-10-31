-- Substring search over the numbers a person has in hand. Without trigram indexes, each would scan the whole table.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX ix_finished_products_serial_trgm ON finished_products USING gin (serial_number gin_trgm_ops);
CREATE INDEX ix_finished_products_dispatch ON finished_products (tenant_id, dispatch_item_id);
CREATE INDEX ix_production_batches_number_trgm ON production_batches USING gin (batch_number gin_trgm_ops);
CREATE INDEX ix_production_batches_facility ON production_batches (tenant_id, facility_id);
CREATE INDEX ix_customers_name_trgm ON customers USING gin (name gin_trgm_ops);
CREATE INDEX ix_dispatch_records_number_trgm ON dispatch_records USING gin (dispatch_number gin_trgm_ops);
CREATE INDEX ix_reversal_items_serials ON dispatch_reversal_items USING gin (serial_numbers jsonb_path_ops);

-- A recall has to reach someone.
ALTER TABLE customers
    ADD COLUMN contact_person TEXT,
    ADD COLUMN email TEXT,
    ADD COLUMN phone TEXT,
    ADD COLUMN address TEXT;

-- A member with no rows here sees every facility, which is how every existing member stays working.
CREATE TABLE membership_facilities (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    membership_id UUID NOT NULL,
    facility_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_membership_facilities UNIQUE (tenant_id, membership_id, facility_id),
    CONSTRAINT fk_membership_facilities_member FOREIGN KEY (tenant_id, membership_id)
        REFERENCES tenant_memberships (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_membership_facilities_facility FOREIGN KEY (tenant_id, facility_id)
        REFERENCES facilities (tenant_id, id)
);
SELECT apply_tenant_standards('membership_facilities');

INSERT INTO permissions (code, module, description) VALUES
    ('RECALL_VIEW', 'Recall', 'View recalls and their customer contacts'),
    ('RECALL_MANAGE', 'Recall', 'Start recalls and record customer contact'),
    ('RECALL_CLOSE', 'Recall', 'Approve exceptions and close recalls');
ALTER TABLE role_permissions NO FORCE ROW LEVEL SECURITY;
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON
    (r.code IN ('ORGANISATION_OWNER', 'ORGANISATION_ADMIN', 'QUALITY_MANAGER')
        AND p.code IN ('RECALL_VIEW', 'RECALL_MANAGE', 'RECALL_CLOSE'))
    OR (r.code = 'PLANT_MANAGER' AND p.code IN ('RECALL_VIEW', 'RECALL_CLOSE'))
    OR (r.code = 'QUALITY_ENGINEER' AND p.code IN ('RECALL_VIEW', 'RECALL_MANAGE'))
    OR (r.code IN ('VIEWER', 'AUDITOR') AND p.code = 'RECALL_VIEW');
ALTER TABLE role_permissions FORCE ROW LEVEL SECURITY;

CREATE TABLE recalls (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    recall_number TEXT NOT NULL,
    recall_date DATE NOT NULL,
    reason TEXT NOT NULL,
    instructions TEXT NOT NULL,
    capa_id UUID,
    status TEXT NOT NULL DEFAULT 'OPEN',
    closure_requested_by UUID,
    closure_requested_at TIMESTAMPTZ,
    closure_note TEXT,
    closed_by UUID,
    closed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_recalls_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_recalls_number UNIQUE (tenant_id, recall_number),
    CONSTRAINT fk_recalls_capa FOREIGN KEY (tenant_id, capa_id) REFERENCES corrective_actions (tenant_id, id),
    CONSTRAINT ck_recalls_status CHECK (status IN ('OPEN', 'PENDING_CLOSURE', 'CLOSED')),
    CONSTRAINT ck_recalls_closed CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL)),
    CONSTRAINT ck_recalls_two_people CHECK (closed_by IS NULL OR closed_by <> closure_requested_by)
);
SELECT apply_tenant_standards('recalls');
CREATE TRIGGER trg_recalls_closed BEFORE UPDATE OR DELETE ON recalls
    FOR EACH ROW WHEN (OLD.status = 'CLOSED') EXECUTE FUNCTION reject_change();

CREATE TABLE recall_batches (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    recall_id UUID NOT NULL,
    production_batch_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_recall_batches UNIQUE (tenant_id, recall_id, production_batch_id),
    CONSTRAINT fk_recall_batches_recall FOREIGN KEY (tenant_id, recall_id) REFERENCES recalls (tenant_id, id),
    CONSTRAINT fk_recall_batches_batch FOREIGN KEY (tenant_id, production_batch_id)
        REFERENCES production_batches (tenant_id, id)
);
SELECT apply_tenant_standards('recall_batches');
CREATE TRIGGER trg_recall_batches_immutable BEFORE UPDATE OR DELETE ON recall_batches
    FOR EACH ROW EXECUTE FUNCTION reject_change();

CREATE TABLE recall_contact_records (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    recall_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    contact_person TEXT,
    contact_details TEXT,
    assigned_to UUID,
    follow_up_on DATE,
    notes TEXT,
    exception_reason TEXT,
    exception_by UUID,
    exception_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_recall_contacts_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_recall_contacts_customer UNIQUE (tenant_id, recall_id, customer_id),
    CONSTRAINT fk_recall_contacts_recall FOREIGN KEY (tenant_id, recall_id) REFERENCES recalls (tenant_id, id),
    CONSTRAINT fk_recall_contacts_customer FOREIGN KEY (tenant_id, customer_id) REFERENCES customers (tenant_id, id),
    CONSTRAINT ck_recall_contacts_status CHECK (status IN ('PENDING', 'CONTACT_ATTEMPTED', 'CONTACTED', 'ACKNOWLEDGED',
        'ACTION_REQUIRED', 'RETURN_IN_PROGRESS', 'RESOLVED', 'UNREACHABLE'))
);
SELECT apply_tenant_standards('recall_contact_records');
CREATE INDEX ix_recall_contacts_followup ON recall_contact_records (tenant_id, recall_id, follow_up_on);

CREATE FUNCTION protect_closed_recall_contact() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF EXISTS (SELECT 1 FROM recalls r
               WHERE r.tenant_id = OLD.tenant_id AND r.id = OLD.recall_id AND r.status = 'CLOSED') THEN
        RAISE EXCEPTION 'recall % is closed', OLD.recall_id USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$;
CREATE TRIGGER trg_recall_contacts_closed BEFORE UPDATE OR DELETE ON recall_contact_records
    FOR EACH ROW EXECUTE FUNCTION protect_closed_recall_contact();

-- Attempts and acknowledgements are history, so a wrong one is corrected by adding another.
CREATE TABLE recall_contact_attempts (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    contact_record_id UUID NOT NULL,
    method TEXT NOT NULL,
    contact_person TEXT,
    contact_details TEXT,
    attempted_at TIMESTAMPTZ NOT NULL,
    outcome TEXT NOT NULL,
    customer_response TEXT,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_recall_attempts_record FOREIGN KEY (tenant_id, contact_record_id)
        REFERENCES recall_contact_records (tenant_id, id),
    CONSTRAINT ck_recall_attempts_method CHECK (method IN ('EMAIL', 'PHONE', 'WHATSAPP', 'OTHER')),
    CONSTRAINT ck_recall_attempts_outcome CHECK (outcome IN ('NO_REPLY', 'REACHED', 'FAILED'))
);
SELECT apply_tenant_standards('recall_contact_attempts');
CREATE INDEX ix_recall_attempts_record ON recall_contact_attempts (tenant_id, contact_record_id, attempted_at);
CREATE TRIGGER trg_recall_attempts_immutable BEFORE UPDATE OR DELETE ON recall_contact_attempts
    FOR EACH ROW EXECUTE FUNCTION reject_change();

CREATE TABLE recall_acknowledgements (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    contact_record_id UUID NOT NULL,
    acknowledged_on DATE NOT NULL,
    acknowledged_by TEXT NOT NULL,
    customer_response TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    updated_by UUID,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_recall_acks_record FOREIGN KEY (tenant_id, contact_record_id)
        REFERENCES recall_contact_records (tenant_id, id)
);
SELECT apply_tenant_standards('recall_acknowledgements');
CREATE INDEX ix_recall_acks_record ON recall_acknowledgements (tenant_id, contact_record_id);
CREATE TRIGGER trg_recall_acks_immutable BEFORE UPDATE OR DELETE ON recall_acknowledgements
    FOR EACH ROW EXECUTE FUNCTION reject_change();

-- These two describe the platform's own jobs and hold no tenant's data, only counts, so they have no tenant column.
CREATE TABLE scheduled_job_locks (
    job_name TEXT PRIMARY KEY,
    locked_until TIMESTAMPTZ NOT NULL,
    locked_at TIMESTAMPTZ NOT NULL,
    locked_by TEXT NOT NULL
);

CREATE TABLE scheduled_job_executions (
    id UUID PRIMARY KEY,
    job_name TEXT NOT NULL,
    instance TEXT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ NOT NULL,
    duration_ms BIGINT NOT NULL,
    tenants_processed INTEGER NOT NULL,
    records_processed INTEGER NOT NULL,
    notifications_generated INTEGER NOT NULL,
    records_skipped INTEGER NOT NULL,
    failure_count INTEGER NOT NULL,
    status TEXT NOT NULL,
    CONSTRAINT ck_job_executions_status CHECK (status IN ('SUCCESS', 'PARTIAL_SUCCESS', 'FAILED'))
);
CREATE INDEX ix_job_executions_latest ON scheduled_job_executions (job_name, started_at DESC);

-- A sign-up that never confirmed leaves an organisation nobody can reach. Audit rows cannot be deleted, so the account
-- is emptied instead: the person's details go, the address becomes free, and the organisation is suspended.
DO $$
BEGIN
    EXECUTE format('CREATE POLICY account_expiry ON tenants FOR UPDATE TO %I USING (true) WITH CHECK (true)', current_user);
    EXECUTE format('CREATE POLICY account_expiry_read ON tenants FOR SELECT TO %I USING (true)', current_user);
    EXECUTE format('CREATE POLICY account_expiry ON tenant_memberships FOR SELECT TO %I USING (true)', current_user);
END;
$$;

CREATE FUNCTION expire_unverified_accounts(cutoff TIMESTAMPTZ) RETURNS INTEGER
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS
$$
DECLARE
    expired UUID[];
BEGIN
    SELECT coalesce(array_agg(id), '{}') INTO expired FROM users
    WHERE status = 'PENDING_VERIFICATION' AND created_at < cutoff;

    UPDATE tenants t SET status = 'SUSPENDED'
    WHERE t.id IN (SELECT tenant_id FROM tenant_memberships WHERE user_id = ANY (expired))
      AND NOT EXISTS (SELECT 1 FROM tenant_memberships m WHERE m.tenant_id = t.id AND m.user_id <> ALL (expired));
    DELETE FROM refresh_tokens WHERE user_id = ANY (expired);
    DELETE FROM account_tokens WHERE user_id = ANY (expired);
    UPDATE users
    SET status = 'DISABLED', email = 'expired-' || id || '@expired.invalid', full_name = 'Expired sign-up',
        password_hash = '!'
    WHERE id = ANY (expired);
    RETURN cardinality(expired);
END;
$$;
REVOKE ALL ON FUNCTION expire_unverified_accounts(TIMESTAMPTZ) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION expire_unverified_accounts(TIMESTAMPTZ) TO ${approle};
