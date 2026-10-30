-- Shared database foundation. Business tables arrive with the phase that needs them.
-- Runs as the schema owner; ${approle} is the unprivileged role the application connects as.

CREATE EXTENSION IF NOT EXISTS citext;   -- case-insensitive emails and codes
CREATE EXTENSION IF NOT EXISTS pg_trgm;  -- batch, material and supplier search

-- Tables created by later migrations become usable by the application role automatically.
-- TRUNCATE and DDL are deliberately not granted. Append-only tables revoke UPDATE/DELETE themselves.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${approle};
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO ${approle};
GRANT USAGE ON SCHEMA public TO ${approle};

-- Attached as a BEFORE UPDATE trigger to every table with an updated_at column.
CREATE FUNCTION set_updated_at() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;

-- Tenant bound to the current transaction, NULL when none is set. Row-level security policies compare
-- tenant_id against this, so a connection without tenant context sees no tenant rows at all.
CREATE FUNCTION current_tenant_id() RETURNS uuid
    LANGUAGE sql STABLE AS
$$
SELECT NULLIF(current_setting('app.tenant_id', true), '')::uuid;
$$;
