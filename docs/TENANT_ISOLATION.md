# Tenant Isolation Strategy

Shared database, shared schema, `tenant_id` on every tenant-owned row. Isolation has several independent
layers, so that one mistake does not expose data.

**Status:** All five layers are implemented and tested.

## Layers

1. **Identity.** The tenant comes from the verified JWT, and is re-checked against an active membership on
   every request (`TenantJwtAuthenticationConverter`).
   The client never selects a tenant by parameter or header. Switching tenant issues a new token through
   `POST /api/v1/auth/switch-tenant`, after a membership check. Request DTOs have no tenant field. The
   optional `tenantId` at login only selects among the user's own memberships.
2. **Application.** `TenantContext.getCurrentTenantId()` is set by a security filter and cleared in
   `finally`. Reading it without a tenant throws rather than returning null.
3. **ORM.** Tenant-owned entities extend a base class using Hibernate's `@TenantId`, which adds the filter to
   queries and sets the column on insert. Native queries get no automatic filter, which is what layer 4
   exists to catch.
4. **Database.** Every tenant-owned table has RLS:

   ```sql
   ALTER TABLE suppliers ENABLE ROW LEVEL SECURITY;
   ALTER TABLE suppliers FORCE ROW LEVEL SECURITY;
   CREATE POLICY tenant_isolation ON suppliers
       USING (tenant_id = current_tenant_id())
       WITH CHECK (tenant_id = current_tenant_id());
   ```

   The application connects as `qualityos_app`, which is neither owner nor `BYPASSRLS`. At transaction start
   `TenantAwareTransactionManager` runs `set_config('app.tenant_id', ?, true)`. That value is
   transaction-local, so it cannot leak through the connection pool. Work outside a transaction sees no
   rows, and `TenantContext` refuses to change inside one. With no tenant set, `current_tenant_id()` is NULL and no rows match:
   a missing context fails closed.
5. **Referential.** Tenant-owned tables carry `UNIQUE (tenant_id, id)`, and foreign keys are composite:
   `FOREIGN KEY (tenant_id, supplier_id) REFERENCES suppliers (tenant_id, id)`. A row therefore cannot point
   at another tenant's record. Business uniqueness is per tenant, as in `UNIQUE (tenant_id, code)`.

## Special cases

| Case | Rule |
|---|---|
| Scheduled jobs | Read the tenant list through a narrow privileged path, then process each with `TenantContext.runAs(tenantId, ...)` in its own transaction |
| Tables queried before a tenant is known | `users` and `refresh_tokens` have no RLS and are reached only by identity services. A user's own memberships and organisation names are readable through the `app.user_id` setting |
| Platform admins (Phase 10) | Separate `/api/v1/platform/**` endpoints, using a distinct role with an explicit policy, and every access audited. No silent impersonation |
| Attachments | Stored under an opaque key. Downloads load the parent record first (so tenant and permission checks apply), then issue a short-lived presigned URL |
| Reports and exports | Same services and RLS as the API, plus facility scoping |
| Facility limits | `FacilityScope`, in the services: lists, reads, writes, attachments and alerts. A refused record answers 404. This narrows access inside an organisation, on top of the tenant layers. It is not enforced by RLS |
| Logs and audit | `audit_logs` has RLS and the app role has only `INSERT` and `SELECT` on it |
| Connection pooling | `app.tenant_id` is only set with local scope. If PgBouncer is added, it must run in transaction mode |

## Verification

`TenantIsolationIT` has two organisations attack each other through the API, then through raw SQL with the
application layer removed. It covers reads, writes, deletes, inserts into another tenant, composite foreign
keys, system role tampering, audit forgery, and pool leakage. `everyTenantOwnedTableEnforcesRowLevelSecurity`
fails the build if a table with a `tenant_id` lacks an enforced policy. `refresh_tokens` is its one exemption.

Both layers were also tested by removing them (2026-10-31):

| Mutation | Result |
|---|---|
| RLS removed from `tenant_memberships` | 5 tests fail, so the suite detects it |
| Hibernate `@TenantId` filter removed | All 22 tests still pass, so RLS holds alone |

Every later phase must add its tenant-owned tables to this suite's API and SQL attacks.
