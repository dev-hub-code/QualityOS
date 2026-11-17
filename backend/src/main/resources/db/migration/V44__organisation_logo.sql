-- An organisation's logo, shown in the app and on its emails. PNG or JPEG only: an SVG can carry
-- script, and the size limit keeps every email that carries it small.
CREATE TABLE tenant_logos (
    tenant_id uuid NOT NULL,
    content_type text NOT NULL,
    data bytea NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_by uuid,
    CONSTRAINT tenant_logos_pkey PRIMARY KEY (tenant_id),
    CONSTRAINT fk_tenant_logos_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT ck_tenant_logos_type CHECK (content_type IN ('image/png', 'image/jpeg')),
    CONSTRAINT ck_tenant_logos_size CHECK (octet_length(data) BETWEEN 1 AND 262144)
);

ALTER TABLE tenant_logos ENABLE ROW LEVEL SECURITY;
ALTER TABLE ONLY tenant_logos FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_logos
    USING ((tenant_id = current_tenant_id())) WITH CHECK ((tenant_id = current_tenant_id()));
-- Emails are sent outside any organisation's context, so the function below reads as the owner.
CREATE POLICY logo_for_mail ON tenant_logos FOR SELECT TO "${flyway:user}" USING (true);

-- The logo an email carries, by the organisation the email is for. Reveals nothing but the image.
CREATE FUNCTION tenant_logo(p_tenant uuid) RETURNS TABLE(content_type text, data bytea)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
    SELECT l.content_type, l.data FROM tenant_logos l WHERE l.tenant_id = p_tenant
$$;
REVOKE ALL ON FUNCTION tenant_logo(uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION tenant_logo(uuid) TO ${approle};
