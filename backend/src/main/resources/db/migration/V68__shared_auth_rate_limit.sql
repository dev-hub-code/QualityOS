-- Sign-in and email attempts per address and minute, shared by every instance and kept across restarts.
-- Not tenant data: the address is not yet anyone's, so there is no row-level security.
CREATE TABLE auth_attempts (
    bucket text NOT NULL,
    window_start timestamp with time zone NOT NULL,
    attempts integer NOT NULL,
    CONSTRAINT auth_attempts_pkey PRIMARY KEY (bucket, window_start)
);

CREATE INDEX idx_auth_attempts_window ON auth_attempts (window_start);

GRANT SELECT, INSERT, DELETE, UPDATE ON TABLE auth_attempts TO ${approle};
