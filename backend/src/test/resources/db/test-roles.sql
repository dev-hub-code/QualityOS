-- Mirrors docker/postgres/init/01-roles.sh for Testcontainers.
CREATE ROLE qualityos_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD 'migrator';
CREATE ROLE qualityos_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS PASSWORD 'app';
ALTER DATABASE qualityos OWNER TO qualityos_migrator;
