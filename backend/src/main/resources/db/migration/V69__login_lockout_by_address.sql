-- Wrong passwords are counted per account and address, so that someone who knows an email address cannot lock its
-- owner out of where they normally sign in. Not tenant data: an account signs in before choosing an organisation.
CREATE TABLE login_failures (
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    address text NOT NULL,
    failures integer NOT NULL,
    locked_until timestamp with time zone,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT login_failures_pkey PRIMARY KEY (user_id, address)
);

-- Addresses an account has signed in from successfully. The account-wide lock never applies to them.
CREATE TABLE login_addresses (
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    address text NOT NULL,
    last_success_at timestamp with time zone NOT NULL,
    CONSTRAINT login_addresses_pkey PRIMARY KEY (user_id, address)
);

CREATE INDEX idx_login_failures_updated ON login_failures (updated_at);
CREATE INDEX idx_login_addresses_last_success ON login_addresses (last_success_at);

GRANT SELECT, INSERT, DELETE, UPDATE ON TABLE login_failures TO ${approle};
GRANT SELECT, INSERT, DELETE, UPDATE ON TABLE login_addresses TO ${approle};
