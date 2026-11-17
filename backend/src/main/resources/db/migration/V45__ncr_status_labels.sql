-- An organisation's own names for the NCR statuses. Only the names change: the workflow and its rules stay fixed.
-- A status without a row keeps its standard name.
CREATE TABLE ncr_status_labels (
    tenant_id uuid NOT NULL,
    status text NOT NULL,
    label text NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_by uuid,
    CONSTRAINT ncr_status_labels_pkey PRIMARY KEY (tenant_id, status),
    CONSTRAINT fk_ncr_status_labels_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT ck_ncr_status_labels_status CHECK (status IN ('OPEN', 'UNDER_INVESTIGATION', 'CONTAINMENT',
        'ACTION_REQUIRED', 'PENDING_VERIFICATION', 'CLOSED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_ncr_status_labels_label CHECK (label = btrim(label) AND char_length(label) BETWEEN 1 AND 40)
);
CREATE UNIQUE INDEX uq_ncr_status_labels_label ON ncr_status_labels (tenant_id, lower(label));

ALTER TABLE ncr_status_labels ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY ncr_status_labels FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ncr_status_labels
    USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));
