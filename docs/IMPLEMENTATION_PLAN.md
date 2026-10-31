# Implementation Plan

The first commercially usable release is the end of Phase 5: secure, configurable IQC end to end, with the
account emails a real customer needs.

| Phase | Scope | Exit criteria | Status |
|---|---|---|---|
| 1 Foundation | Project structure, Docker, Flyway, response model, errors, logging, OpenAPI, health | Stack starts and all checks pass | **Done** |
| 2 Identity and tenancy | Registration, login, JWT, refresh rotation, onboarding, memberships, RBAC, TenantContext, RLS, audit log | Two organisations proven isolated by tests | **Done** |
| 3 Master data | Facilities, departments, suppliers, products, materials, UoM, purchase orders, receipts, attachments | PO to receipt workflow in the UI | **Done** |
| 4 Inspection engine | Template builder and versioning, evaluation engine, sampling, execution, approval, disposition, report | **IQC usable end to end on a phone** | **Done** |
| 5 Notifications and account email | In-app and email notifications, invitations, email verification, password reset, IP rate limiting, scheduled jobs | Inspection events notify the right people, and a user can join and recover an account by email | **Done** |
| 6 NCR and CAPA | Lifecycles, containment, root cause, verification, closure | Failed inspection flows through to closed CAPA | Next |
| 7 Traceability | Batch graph, forward and backward trace, timeline, search | Raw material to dispatch traced | |
| 8 Calibration and audit | Instruments, QR, calibration records, audits, findings | Overdue instruments notify and audit findings raise NCRs | |
| 9 Analytics | Dashboard, scorecards, Pareto, PDF and Excel exports | Reports honour tenant and facility scope | |
| 10 Subscription | Plans, trials, limits, invoices | Limits enforced, prices editable by admin | |
| 11 Production | Terraform, ECS, RDS, S3, Secrets Manager, CI/CD, monitoring, backups | Deployed and restore-tested | Deferred |

Later: Module 12 (AI) after Phase 9, because it depends on accumulated data. SMS and WhatsApp stay behind
a notification channel interface.

Phase 11 is deferred until the owner asks for it. Until then, development targets the local Docker Compose stack only.

## Known gaps

Deliberately left for a later phase. None of them weakens tenant isolation.

| Gap | Planned |
|---|---|
| Someone can still deliberately lock out a known email (10 failures, 15 minutes), though the IP limit slows it down. A reset link unlocks the account | Review after real use |
| The IP rate limit is held per backend instance, so several instances multiply it. Shared limits need Redis or the load balancer | With production work |
| An unverified email address only shows a reminder; it does not restrict the account | Decide before opening public registration |
| A material receipt does not notify inspectors; they see it in the inspection queue | When a user needs it |
| Notifications are polled once a minute, with no live push. Only email and in-app channels exist | When a user needs it |
| Emails are plain text and sent in English only | Before customer launch |
| Platform roles (`SUPER_ADMIN`, `PLATFORM_ADMIN`, `SUPPORT_ADMIN`) are not implemented | Phase 10, with the admin panel |
| Registration reveals that an email already has an account. Password reset and invitations do not | Accepted for now |
| Sampling is fixed, percentage or every unit; AQL tables (such as ISO 2859-1) are not built | When a customer needs them |
| A one-person organisation cannot finish an inspection, because the inspector cannot review their own work | Make it a tenant setting in Phase 9 |
| The inspection report is a printable page, not a generated PDF | Phase 9, with PDF exports |
| A template can only be chosen by hand; none is picked automatically for a material | When a user needs it |
| Inspections start from received material only. IPQC and FQC need production batches | Phase 7, with traceability |
| `tenant_settings` and supplier scorecards are not built | Phase 9 |
| Attachments use local disk; S3 sits behind `FileStorage` and is not implemented | With production work |
| Lists show only active records, with no way to restore an archived one | When a user needs it |
| Facility, supplier, material and product pickers load the first 100 records | Add search when a tenant exceeds this |
| Facility sub-records (departments, lines, shifts), supplier contacts, material-to-product mappings, and new categories and units are not audited individually | Review in Phase 9 |

## Definition of done for each phase

Migrations, validation, error handling, audit events on critical actions, unit and integration tests,
tenant isolation tests for every new tenant-owned table, updated docs, and a report of open issues.
