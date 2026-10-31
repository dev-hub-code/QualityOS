-- Extensions, application role access and shared functions.
-- ${approle} is the unprivileged role the application connects as.

SET LOCAL check_function_bodies = off;

CREATE EXTENSION IF NOT EXISTS citext WITH SCHEMA public;

COMMENT ON EXTENSION citext IS 'data type for case-insensitive character strings';

CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

COMMENT ON EXTENSION pg_trgm IS 'text similarity measurement and index searching based on trigrams';

-- Tenant bound to the current transaction, NULL when none is set. Row-level security policies compare
-- tenant_id against this, so a connection without tenant context sees no tenant rows at all.
CREATE FUNCTION current_tenant_id() RETURNS uuid
    LANGUAGE sql STABLE
    AS $$
SELECT NULLIF(current_setting('app.tenant_id', true), '')::uuid;
$$;

-- Attached as a BEFORE UPDATE trigger to every table with an updated_at column.
CREATE FUNCTION set_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;

GRANT USAGE ON SCHEMA public TO ${approle};
