-- Tables added by later migrations become usable by the application role. Last, so that
-- the explicit grants above stay exactly as written.
-- ${approle} is the unprivileged role the application connects as.

ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT,USAGE ON SEQUENCES TO ${approle};

ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT,INSERT,DELETE,UPDATE ON TABLES TO ${approle};


