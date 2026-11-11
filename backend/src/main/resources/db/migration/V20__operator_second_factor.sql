-- Operators sign in with their password and a code from an authenticator app. The secret behind the codes is kept
-- here, encrypted by the application, and the application role reaches it only through these functions, as it does
-- platform_admins itself. An operator cannot use the Control Center until they have enrolled.

CREATE TABLE platform_second_factors (
    user_id uuid NOT NULL,
    secret bytea NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    confirmed_at timestamp with time zone,
    -- The last time step a code was accepted for, so that a code works once.
    last_used_step bigint,
    CONSTRAINT platform_second_factors_pkey PRIMARY KEY (user_id),
    CONSTRAINT fk_platform_second_factors_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- Undoes the default grant on new tables: the application reaches this table through the functions alone.
REVOKE ALL ON TABLE platform_second_factors FROM ${approle};

-- NONE, PENDING (a secret was issued and no code has confirmed it) or ENROLLED, for the current operator.
CREATE FUNCTION platform_second_factor_state() RETURNS text
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
BEGIN
    PERFORM require_platform_admin();
    RETURN COALESCE((SELECT CASE WHEN confirmed_at IS NULL THEN 'PENDING' ELSE 'ENROLLED' END
                     FROM platform_second_factors WHERE user_id = current_app_user_id()), 'NONE');
END;
$$;

-- Starts enrolment, or starts it again with a new secret. An enrolled factor is only taken away by a reset.
CREATE FUNCTION platform_second_factor_begin(p_secret bytea) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
BEGIN
    PERFORM require_platform_admin();
    IF EXISTS (SELECT 1 FROM platform_second_factors
               WHERE user_id = current_app_user_id() AND confirmed_at IS NOT NULL) THEN
        RAISE EXCEPTION 'second factor already enrolled' USING ERRCODE = 'check_violation';
    END IF;
    INSERT INTO platform_second_factors (user_id, secret) VALUES (current_app_user_id(), p_secret)
    ON CONFLICT (user_id) DO UPDATE SET secret = EXCLUDED.secret, created_at = now(), last_used_step = NULL;
END;
$$;

-- The current operator's unconfirmed secret, to check the first code against. Null once enrolled or before starting.
CREATE FUNCTION platform_second_factor_pending() RETURNS bytea
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
BEGIN
    PERFORM require_platform_admin();
    RETURN (SELECT secret FROM platform_second_factors WHERE user_id = current_app_user_id() AND confirmed_at IS NULL);
END;
$$;

CREATE FUNCTION platform_second_factor_confirm(p_step bigint) RETURNS boolean
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
BEGIN
    PERFORM require_platform_admin();
    UPDATE platform_second_factors SET confirmed_at = now(), last_used_step = p_step
    WHERE user_id = current_app_user_id() AND confirmed_at IS NULL;
    RETURN FOUND;
END;
$$;

-- At sign-in there is no session yet, so this takes the account. Only an active operator's enrolled factor counts:
-- anyone else signs in with their password alone.
CREATE FUNCTION platform_second_factor_for_login(p_user uuid) RETURNS bytea
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
BEGIN
    RETURN (SELECT f.secret FROM platform_second_factors f
            JOIN platform_admins a ON a.user_id = f.user_id
            WHERE f.user_id = p_user AND f.confirmed_at IS NOT NULL AND a.deactivated_at IS NULL);
END;
$$;

-- Spends a code. False when that step, or a later one, was already used, so a code seen once is no use again.
CREATE FUNCTION platform_second_factor_use(p_user uuid, p_step bigint) RETURNS boolean
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
BEGIN
    UPDATE platform_second_factors SET last_used_step = p_step
    WHERE user_id = p_user AND confirmed_at IS NOT NULL AND (last_used_step IS NULL OR last_used_step < p_step);
    RETURN FOUND;
END;
$$;

-- For a lost device. A Super Admin takes the factor away, and the operator enrols again on their next visit.
CREATE FUNCTION platform_second_factor_reset(p_user uuid) RETURNS text
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
BEGIN
    PERFORM require_platform_role(ARRAY['SUPER_ADMIN']);
    IF NOT EXISTS (SELECT 1 FROM platform_admins WHERE user_id = p_user) THEN
        RETURN 'NOT_FOUND';
    END IF;
    DELETE FROM platform_second_factors WHERE user_id = p_user;
    RETURN 'OK';
END;
$$;

-- The operator list now says whether each has enrolled.
DROP FUNCTION platform_operators();
CREATE FUNCTION platform_operators() RETURNS TABLE(user_id uuid, email text, full_name text, role text, added_at timestamp with time zone, deactivated_at timestamp with time zone, added_by text, last_activity timestamp with time zone, second_factor text)
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
#variable_conflict use_column
BEGIN
    PERFORM require_platform_role(ARRAY['SUPER_ADMIN', 'READ_ONLY_AUDITOR']);
    RETURN QUERY
    SELECT a.user_id, u.email, u.full_name, a.role, a.created_at, a.deactivated_at, b.email,
           (SELECT max(l.occurred_at) FROM audit_logs l WHERE l.user_id = a.user_id),
           CASE WHEN f.user_id IS NULL THEN 'NONE' WHEN f.confirmed_at IS NULL THEN 'PENDING' ELSE 'ENROLLED' END
    FROM platform_admins a
    JOIN users u ON u.id = a.user_id
    LEFT JOIN users b ON b.id = a.added_by
    LEFT JOIN platform_second_factors f ON f.user_id = a.user_id
    ORDER BY a.deactivated_at IS NOT NULL, u.email;
END;
$$;

REVOKE ALL ON FUNCTION platform_second_factor_state() FROM PUBLIC;
GRANT ALL ON FUNCTION platform_second_factor_state() TO ${approle};
REVOKE ALL ON FUNCTION platform_second_factor_begin(p_secret bytea) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_second_factor_begin(p_secret bytea) TO ${approle};
REVOKE ALL ON FUNCTION platform_second_factor_pending() FROM PUBLIC;
GRANT ALL ON FUNCTION platform_second_factor_pending() TO ${approle};
REVOKE ALL ON FUNCTION platform_second_factor_confirm(p_step bigint) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_second_factor_confirm(p_step bigint) TO ${approle};
REVOKE ALL ON FUNCTION platform_second_factor_for_login(p_user uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_second_factor_for_login(p_user uuid) TO ${approle};
REVOKE ALL ON FUNCTION platform_second_factor_use(p_user uuid, p_step bigint) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_second_factor_use(p_user uuid, p_step bigint) TO ${approle};
REVOKE ALL ON FUNCTION platform_second_factor_reset(p_user uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_second_factor_reset(p_user uuid) TO ${approle};
REVOKE ALL ON FUNCTION platform_operators() FROM PUBLIC;
GRANT ALL ON FUNCTION platform_operators() TO ${approle};
