# Implementation Plan

The first commercially usable release is the end of Phase 4: secure, configurable IQC end to end.

| Phase | Scope | Exit criteria | Status |
|---|---|---|---|
| 1 Foundation | Project structure, Docker, Flyway, response model, errors, logging, OpenAPI, health | Stack starts and all checks pass | **Done** |
| 2 Identity and tenancy | Registration, login, JWT, refresh rotation, onboarding, memberships, RBAC, TenantContext, RLS, audit log | Two organisations proven isolated by tests | **Done** |
| 3 Master data | Facilities, departments, suppliers, products, materials, UoM, purchase orders, receipts, attachments | PO to receipt workflow in the UI | Next |
| 4 Inspection engine | Template builder and versioning, evaluation engine, sampling, execution, approval, disposition, report | **IQC usable end to end on a phone** | |
| 5 NCR and CAPA | Lifecycles, containment, root cause, verification, closure | Failed inspection flows through to closed CAPA | |
| 6 Traceability | Batch graph, forward and backward trace, timeline, search | Raw material to dispatch traced | |
| 7 Calibration and audit | Instruments, QR, calibration records, audits, findings | Overdue instruments notify and audit findings raise NCRs | |
| 8 Analytics | Dashboard, scorecards, Pareto, PDF and Excel exports | Reports honour tenant and facility scope | |
| 9 Subscription | Plans, trials, limits, invoices | Limits enforced, prices editable by admin | |
| 10 Production | Terraform, ECS, RDS, S3, Secrets Manager, CI/CD, monitoring, backups | Deployed and restore-tested | Deferred |

Later: Module 12 (AI) after Phase 8, because it depends on accumulated data. SMS and WhatsApp stay behind
a notification channel interface. In-app and email notifications arrive with Phase 4.

Phase 10 is deferred until the owner asks for it. Until then, development targets the local Docker Compose stack only.

## Known gaps from Phase 2

Deliberately left for a later phase. None of them weakens tenant isolation.

| Gap | Planned |
|---|---|
| No email verification or password reset, because no email delivery exists yet | Phase 4 (email notifications) |
| New users receive an admin-set initial password rather than an invitation link, and are not forced to change it | Phase 4 |
| Login throttling is per account only (10 failures, 15 minutes). Someone can deliberately lock out a known email, and there is no IP rate limit | Phase 4, with invitations |
| Platform roles (`SUPER_ADMIN`, `PLATFORM_ADMIN`, `SUPPORT_ADMIN`) are not implemented | Phase 9, with the admin panel |
| Expired `refresh_tokens` rows are not purged | First scheduled-job work, Phase 4 |
| Registration reveals that an email already has an account | Accepted for now; revisit with email verification |

## Definition of done for each phase

Migrations, validation, error handling, audit events on critical actions, unit and integration tests,
tenant isolation tests for every new tenant-owned table, updated docs, and a report of open issues.
