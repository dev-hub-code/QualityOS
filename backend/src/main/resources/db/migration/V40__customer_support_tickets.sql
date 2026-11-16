-- Customers raise their own support tickets and follow them. Tickets that support opens stay private to operators,
-- and a note reaches the customer only when the operator shares it as a reply.
ALTER TABLE support_tickets ADD COLUMN raised_by_customer boolean DEFAULT false NOT NULL;
ALTER TABLE support_notes ADD COLUMN shared boolean DEFAULT false NOT NULL;

DROP FUNCTION platform_support_note(uuid, text);
DROP FUNCTION platform_support_notes(uuid);
DROP FUNCTION platform_support_tickets(uuid, text, integer, integer);

-- A shared note is a reply the customer sees. Only a ticket the customer raised can have one.
CREATE FUNCTION platform_support_note(p_id uuid, p_body text, p_shared boolean) RETURNS text
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
DECLARE
    ticket support_tickets%ROWTYPE;
BEGIN
    PERFORM require_platform_role(ARRAY['SUPER_ADMIN', 'SUPPORT_ADMIN']);
    SELECT * INTO ticket FROM support_tickets WHERE id = p_id FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'NOT_FOUND';
    END IF;
    IF ticket.status = 'CLOSED' THEN
        RETURN 'CLOSED';
    END IF;
    IF p_shared AND NOT ticket.raised_by_customer THEN
        RETURN 'INTERNAL';
    END IF;
    INSERT INTO support_notes (id, ticket_id, author_id, body, shared)
    VALUES (gen_random_uuid(), p_id, current_app_user_id(), p_body, p_shared);
    UPDATE support_tickets SET updated_at = now() WHERE id = p_id;
    RETURN 'OK';
END;
$$;

CREATE FUNCTION platform_support_notes(p_id uuid) RETURNS TABLE(id uuid, author text, body text, shared boolean, created_at timestamp with time zone)
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
#variable_conflict use_column
BEGIN
    PERFORM require_platform_admin();
    RETURN QUERY
    SELECT n.id, u.email, n.body, n.shared, n.created_at
    FROM support_notes n JOIN users u ON u.id = n.author_id
    WHERE n.ticket_id = p_id
    ORDER BY n.created_at, n.id;
END;
$$;

CREATE FUNCTION platform_support_tickets(p_tenant uuid, p_status text, p_limit integer, p_offset integer) RETURNS TABLE(id uuid, tenant_id uuid, organisation text, subject text, status text, opened_by text, raised_by_customer boolean, created_at timestamp with time zone, updated_at timestamp with time zone, version bigint, total bigint)
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
#variable_conflict use_column
BEGIN
    PERFORM require_platform_admin();
    RETURN QUERY
    SELECT s.id, s.tenant_id, t.name, s.subject, s.status, u.email, s.raised_by_customer, s.created_at, s.updated_at,
           s.version, count(*) OVER ()
    FROM support_tickets s
    JOIN tenants t ON t.id = s.tenant_id
    JOIN users u ON u.id = s.opened_by
    WHERE (p_tenant IS NULL OR s.tenant_id = p_tenant)
      AND (p_status IS NULL OR s.status = p_status)
    ORDER BY s.updated_at DESC, s.id DESC
    LIMIT p_limit OFFSET p_offset;
END;
$$;

-- Who to tell about an operator's change to a ticket the customer raised.
CREATE FUNCTION platform_support_ticket_contact(p_id uuid) RETURNS TABLE(tenant_id uuid, opened_by uuid, subject text, status text, raised_by_customer boolean)
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
#variable_conflict use_column
BEGIN
    PERFORM require_platform_admin();
    RETURN QUERY SELECT s.tenant_id, s.opened_by, s.subject, s.status, s.raised_by_customer
    FROM support_tickets s WHERE s.id = p_id;
END;
$$;

-- The functions below act for the signed-in member of the current organisation. p_all is true for someone who
-- manages the organisation, who sees every ticket it raised; anyone else sees only their own.
CREATE FUNCTION customer_support_open(p_subject text, p_message text) RETURNS uuid
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
DECLARE
    ticket UUID := gen_random_uuid();
BEGIN
    IF current_tenant_id() IS NULL OR current_app_user_id() IS NULL THEN
        RAISE EXCEPTION 'A support ticket needs a signed-in member' USING ERRCODE = '42501';
    END IF;
    INSERT INTO support_tickets (id, tenant_id, subject, opened_by, raised_by_customer)
    VALUES (ticket, current_tenant_id(), p_subject, current_app_user_id(), true);
    INSERT INTO support_notes (id, ticket_id, author_id, body, shared)
    VALUES (gen_random_uuid(), ticket, current_app_user_id(), p_message, true);
    RETURN ticket;
END;
$$;

CREATE FUNCTION customer_support_tickets(p_all boolean) RETURNS TABLE(id uuid, subject text, status text, opened_by text, created_at timestamp with time zone, updated_at timestamp with time zone)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
    SELECT s.id, s.subject, s.status, u.full_name, s.created_at, s.updated_at
    FROM support_tickets s JOIN users u ON u.id = s.opened_by
    WHERE s.tenant_id = current_tenant_id() AND s.raised_by_customer
      AND (p_all OR s.opened_by = current_app_user_id())
    ORDER BY s.updated_at DESC, s.id DESC;
$$;

-- Only shared notes. An author who is not a member here is support, shown without a name.
CREATE FUNCTION customer_support_messages(p_id uuid, p_all boolean) RETURNS TABLE(id uuid, author text, from_support boolean, body text, created_at timestamp with time zone)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
    SELECT n.id,
           CASE WHEN m.user_id IS NULL THEN 'ProductionOS support' ELSE u.full_name END,
           m.user_id IS NULL,
           n.body, n.created_at
    FROM support_tickets s
    JOIN support_notes n ON n.ticket_id = s.id AND n.shared
    JOIN users u ON u.id = n.author_id
    LEFT JOIN tenant_memberships m ON m.tenant_id = s.tenant_id AND m.user_id = n.author_id
    WHERE s.id = p_id AND s.tenant_id = current_tenant_id() AND s.raised_by_customer
      AND (p_all OR s.opened_by = current_app_user_id())
    ORDER BY n.created_at, n.id;
$$;

-- A reply from the customer puts a ticket that was waiting on them, or resolved, back in support's queue.
CREATE FUNCTION customer_support_reply(p_id uuid, p_body text, p_all boolean) RETURNS text
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO 'public'
    AS $$
DECLARE
    ticket support_tickets%ROWTYPE;
BEGIN
    SELECT * INTO ticket FROM support_tickets
    WHERE id = p_id AND tenant_id = current_tenant_id() AND raised_by_customer
      AND (p_all OR opened_by = current_app_user_id())
    FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'NOT_FOUND';
    END IF;
    IF ticket.status = 'CLOSED' THEN
        RETURN 'CLOSED';
    END IF;
    INSERT INTO support_notes (id, ticket_id, author_id, body, shared)
    VALUES (gen_random_uuid(), p_id, current_app_user_id(), p_body, true);
    UPDATE support_tickets
    SET status = CASE WHEN status IN ('WAITING_FOR_CUSTOMER', 'RESOLVED') THEN 'OPEN' ELSE status END,
        version = version + 1, updated_at = now()
    WHERE id = p_id;
    RETURN 'OK';
END;
$$;

REVOKE ALL ON FUNCTION platform_support_note(uuid, text, boolean) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_support_note(uuid, text, boolean) TO ${approle};
REVOKE ALL ON FUNCTION platform_support_notes(uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_support_notes(uuid) TO ${approle};
REVOKE ALL ON FUNCTION platform_support_tickets(uuid, text, integer, integer) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_support_tickets(uuid, text, integer, integer) TO ${approle};
REVOKE ALL ON FUNCTION platform_support_ticket_contact(uuid) FROM PUBLIC;
GRANT ALL ON FUNCTION platform_support_ticket_contact(uuid) TO ${approle};
REVOKE ALL ON FUNCTION customer_support_open(text, text) FROM PUBLIC;
GRANT ALL ON FUNCTION customer_support_open(text, text) TO ${approle};
REVOKE ALL ON FUNCTION customer_support_tickets(boolean) FROM PUBLIC;
GRANT ALL ON FUNCTION customer_support_tickets(boolean) TO ${approle};
REVOKE ALL ON FUNCTION customer_support_messages(uuid, boolean) FROM PUBLIC;
GRANT ALL ON FUNCTION customer_support_messages(uuid, boolean) TO ${approle};
REVOKE ALL ON FUNCTION customer_support_reply(uuid, text, boolean) FROM PUBLIC;
GRANT ALL ON FUNCTION customer_support_reply(uuid, text, boolean) TO ${approle};
