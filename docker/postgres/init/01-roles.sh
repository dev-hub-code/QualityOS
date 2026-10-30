#!/bin/sh
# Runs once, when the Postgres data volume is first initialised.
# The migrator owns the schema; the application role only reads and writes rows, so that row-level
# security cannot be bypassed by table ownership.
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v migrator="$DB_MIGRATION_USER" -v migrator_password="$DB_MIGRATION_PASSWORD" \
    -v app="$DB_APP_USER" -v app_password="$DB_APP_PASSWORD" \
    -v database="$POSTGRES_DB" <<'SQL'
CREATE ROLE :"migrator" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'migrator_password';
CREATE ROLE :"app" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS PASSWORD :'app_password';
ALTER DATABASE :"database" OWNER TO :"migrator";
SQL
