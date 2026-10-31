# Architecture

## Overview

```
Browser ──> Next.js (web + BFF) ──> Spring Boot modular monolith ──> PostgreSQL
                                          └──> S3 (attachments, with production work)
```

- **Modular monolith.** One deployable. Modules sit under `com.qualityos.<module>` and are the future
  extraction boundary. A module calls another only through its service interface, never its repositories
  or entities. `ModuleBoundariesTest` (ArchUnit) enforces this.
- **Browsers talk only to Next.js.** Next route handlers call the backend server to server, so no CORS is
  needed, the API is not publicly exposed, and from Phase 2 tokens stay in httpOnly cookies out of reach
  of JavaScript. `BACKEND_URL` is read at runtime, so one image works in every environment. Next
  rewrites would not allow this, because they are fixed at build time.
- **Two database roles.** `qualityos_migrator` owns the schema and runs Flyway. `qualityos_app` serves
  traffic with row access only. See [TENANT_ISOLATION.md](TENANT_ISOLATION.md).

## Package structure

`com.qualityos` follows the brief, section 4. Modules are created in the phase that needs them rather than
as empty folders. Two choices beyond the brief: correlation logging lives in `common.config`, and the
system info endpoint in `common.system`.

## Verified technology versions

Checked against Maven Central and npm on 2026-10-31, then proven by building and running.

| Component | Version | Note |
|---|---|---|
| Java / Node | 25 / 26 | As specified |
| Spring Boot | 4.1.2 | Latest stable. 4.2 is still RC |
| Spring Framework / Security | 7.0 / 7.1 | Managed by Boot |
| Hibernate | 7.4 | Managed by Boot |
| Jackson | 3.1 | Boot 4 default. Packages moved to `tools.jackson`; annotations remain `com.fasterxml` |
| Flyway | 12.4 | Boot-managed. Do not override with the newer 13.x |
| PostgreSQL | 18 | |
| springdoc | 3.2.0 | Boot 4 line |
| MapStruct | 1.6.3 | Latest stable. 1.7 is beta. Tested with Lombok 1.18.48 on Java 25 |
| Testcontainers | 2.0.5 | Boot-managed |
| Next.js / React | 16.4 / 19.3 | |
| TypeScript | 5.9 | See below |
| Tailwind | 4 | |
| Vitest | 5 | Chosen over Jest: simpler TS and ESM setup |

## Compatibility findings

| Finding | Decision |
|---|---|
| Boot 4 split auto-configuration into modules | Use `spring-boot-starter-webmvc`, `-flyway`, `-webmvc-test`; test annotations moved packages |
| Testcontainers 2 renamed artifacts and moved classes | `testcontainers-postgresql`, `org.testcontainers.postgresql.PostgreSQLContainer` |
| Java 23+ does not auto-discover annotation processors | Explicit `annotationProcessorPaths` in `pom.xml` |
| TypeScript 7 is latest on npm, but `create-next-app` 16.4 pins `^5` | Stay on 5.9 until Next adopts 7 |
| `@vitejs/plugin-react` 6 conflicts with the Babel tree under `shadcn` | Not used; Vitest handles JSX natively |
| Postgres 18 image moved its data directory | Volume mounts `/var/lib/postgresql` |
| `eclipse-temurin:25-jre` has no `curl` | Installed for the container health check |
| Boot 4 renamed the resource server starter | `spring-boot-starter-security-oauth2-resource-server` |
| Next 16 renamed `middleware` to `proxy` | `src/proxy.ts` |
| Next 16.4 template enables `cacheComponents` | Kept. Data is dynamic by default; use `use cache` deliberately |
| shadcn now generates Base UI components and the `cn` package (published by shadcn; new, version 0.5) | Kept. Replace with `clsx` + `tailwind-merge` if it proves unstable |
| TanStack Table is pinned to v8 | v9 changed the API and shadcn recipes target v8. Revisit on a planned upgrade |

Libraries with no use yet (Recharts) are added in the phase that uses them.

## Key decisions

| Topic | Decision | Reason |
|---|---|---|
| Tenant and organisation | One `tenants` row per organisation; `/organizations` exposes it | The brief lists both concepts but only a `tenants` table. Splitting later is a contained migration |
| Identity | Self-issued RS256 JWTs validated by the OAuth2 Resource Server | No external IdP dependency; an enterprise IdP can be added later |
| AWS compute | ECS Fargate | Sufficient for one monolith, with far less to operate than EKS |
| Spelling | US in code and API (`organization`), British in UI text | Matches the brief's API paths |
| Operators | `BETWEEN` = min/max; `IN_RANGE` = nominal ± tolerance | The brief lists both without distinguishing them |
| Measurements | `NUMERIC(18,6)`; Java `BigDecimal` | Never floating point |
| IDs | UUIDv7 from the application | Index-friendly and non-enumerable |
| Document numbers | Per-tenant counter in `document_sequences`, row-locked | Sequential per tenant with no cross-tenant leakage |
| Error status for another tenant's record | 404, never 403 | Does not reveal the record exists |
| Reports | PDF and Excel generated on the server | Tenant enforcement stays in one place |
| Passwords | Argon2id, 10 to 128 characters | Spring Security default parameters |
| Authorisation data | Loaded per request, not stored in the JWT | Revocation is immediate; costs one query per request, cacheable later |
| `order_inserts` | Off | Modules link by plain UUID, so Hibernate cannot see the dependencies needed to reorder safely |

## Operational behaviour (Phase 1, tested)

- Liveness is independent of the database. Readiness includes it and returns 503 within about 3s during
  an outage, because Hikari's `connection-timeout` is set to 3s.
- The application recovers without restart when Postgres returns.
- The `prod` profile logs ECS JSON, disables OpenAPI and hides health details. It has no credential
  defaults, so the app refuses to start without them.
- Every response has an `X-Correlation-Id`, also present in logs and the response body.

## Assumptions and open decisions

- Data residency: AWS `ap-south-1` (Mumbai) assumed. Confirm if Phase 11 (deferred) goes ahead.
- Payment gateway is deferred (Razorpay or similar), behind an interface.
- GST invoice requirements need review by an accountant before invoices are issued to customers.
- Offline inspection is not in the brief. Shop floors with poor connectivity may need it; revisit after
  the IQC pilot.

## Email

`common/mail/EmailService` is the only way email leaves the application. It chooses the language (the user's choice,
then the organisation's default, then English), renders, sends, and writes one `email_delivery_logs` row per attempt.
Bodies are never stored, because they can hold one-time links.

Templates live in `resources/email-templates/<language>/<category>/`. Each has an HTML fragment and a `.txt` version,
whose first line is the subject, and sits inside that language's `layout.html`. They use Mustache: `{{value}}` is
escaped in HTML. A missing variable fails the send rather than leaving a blank.

To add a language, add it to `qualityos.mail.languages` and add a folder. Any file it lacks falls back to English.

Account emails retry three times in the background. Notification emails keep the existing retry schedule of about an
hour. Notifications store their template and variables, not finished text, so the language is chosen when they are sent.

## Scheduled jobs

Every job runs through `JobRunner`. It takes a row in `scheduled_job_locks` with a lease, so one instance runs a job at a
time and a dead instance's lock lapses. A finished run holds the lock for 90% of its interval, so a second instance on the
same schedule does not repeat it. A failed run releases it at once. Tenant work runs per tenant, so one failure does not stop
the others, and each run writes a row to `scheduled_job_executions`, kept for 30 days. `JobMonitor` warns when a job misses
three runs, and reports `STALE` in health.

A new job also needs an entry in `JobMonitor`, which holds its own list of what should be running.
