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
| 6 NCR and CAPA | Lifecycles, containment, root cause, verification, closure | Failed inspection flows through to closed CAPA | **Done** |
| 7 Traceability | Batch graph, forward and backward trace, timeline, search | Raw material to dispatch traced | **Done** |
| 8 Calibration and audit | Instruments, QR, calibration records, audits, findings | Overdue instruments notify and audit findings raise NCRs | **Done** |
| 9 Analytics | Dashboard, scorecards, Pareto, PDF and Excel exports | Reports honour tenant and facility scope | **Done** |
| 10 Subscription | Plans, trials, limits, invoices | Limits enforced, prices editable by admin | **Done** |
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
| An expired sign-up is emptied after 30 days, not deleted, because its audit rows cannot be removed. Its suspended organisation row stays. With unverified login allowed, nothing is expired | By design |
| Account-level audit events (verification, password reset) have no tenant, so no organisation can read them in-app | With the next admin work |
| A material receipt does not notify inspectors; they see it in the inspection queue | When a user needs it |
| Notifications are polled once a minute, with no live push. Only email and in-app channels exist | When a user needs it |
| Hindi and Marathi email text was machine-drafted and needs review by a native speaker before customers see it | Before customer launch |
| Some notifications have no dedicated template (NCR ready for closure, CAPA actions, verification, send-back, corrections). Their email has a translated frame around English text | When a user needs it |
| Email delivery is recorded in `email_delivery_logs`, but there is no screen for it. The app itself is still English only | With the next admin work |
| Organisation branding in email is the organisation name only. There is no logo upload | When a user needs it |
| With verification switched off for private use, registration reveals that an email has an account | Private use only |
| Sampling is fixed, percentage or every unit; AQL tables (such as ISO 2859-1) are not built | When a customer needs them |
| A one-person organisation cannot finish an inspection, close an NCR or verify its own CAPA, because each needs a second person | With `tenant_settings` |
| NCR and CAPA workflows are fixed: a critical or major NCR always needs a closed CAPA and a verified fix. There are no per-tenant settings | With `tenant_settings` |
| An NCR raised on a held lot has no rejected quantity, and a later reject does not fill it in | Review after real use |
| Completing a CAPA action needs `CAPA_MANAGE`, so an action owner without it must ask someone who has it | Review after real use |
| Overdue reminders cover the CAPA as a whole, not each action | When a user needs it |
| Only inspection failures raise NCRs automatically. Evidence is attached to the CAPA, not to each action | When a user needs it |
| A single inspection's report is a printable page, not a generated PDF. The inspection report across a period is one | When a user needs it |
| A template can only be chosen by hand; none is picked automatically for a material | When a user needs it |
| Production batches take IPQC and FQC inspections only. OQC and the other template types have no production flow | When a user needs it |
| A template cannot yet be limited to a product in the UI, and quantities consumed are not converted between units | When a user needs it |
| A customer who returned everything still shows as having received the batch. This is deliberate: a recall should reach them | By design |
| The return screen takes one line at a time, though the API takes several. Credit notes and refunds are separate and not built | When a user needs it |
| Search covers lots, batches, products and serial numbers, not NCRs, CAPAs or suppliers. Serial search reports where a unit is now; earlier customers are on its detail page | When a user needs it |
| A lot's internal batch number cannot be corrected, because other records refer to it by number. It is generated, so it cannot be mistyped | By design |
| A corrected lot keeps its original purchase order line, and a finished NCR keeps its original supplier and material | By design: history is preserved |
| A correction of an inspected lot does not trigger reinspection. It needs two approvers, who decide whether to act | Review after real use |
| Naming serial numbers on a dispatch is optional, so a batch can ship without saying which units went. A trace then shows the customer by batch, not by unit | Review after real use |
| A dispatch only counts as shipped once someone confirms it. Traces flag it until then, and managers get a daily reminder after three days | Review after real use |
| A limited member sees only records placed in their facilities. Anything that cannot be placed stays with unlimited members: older manual NCRs and CAPAs, and new ones raised without a facility | By design |
| A dispatch carrying goods from two plants is hidden from a member limited to one of them, as is its return | Review after real use |
| A lot from another plant cannot be consumed by a member limited elsewhere. An unlimited member must record a transfer like that | Review after real use |
| A batch trace can still show inspections and NCRs of a lot from another plant, because lineage must stay whole | By design |
| The recall PDF does not shape Devanagari: names stay readable, but conjuncts do not form correctly | Before Hindi or Marathi customer names matter |
| A dispatch after a recall starts is not blocked. Its customer joins the recall when closure is requested or approved | Review after real use |
| Recalls start from one batch in the UI, though the API takes several. Linking a CAPA is API only | When a user needs it |
| Scheduled job history has no screen. A silent job shows as `STALE` in health and as a log warning | With the next admin work |
| Recording an instrument on an inspection is optional, and applies to the whole inspection, not each measurement | Review after real use |
| A failed calibration flags the inspections in doubt and notifies people, but does not raise an NCR or reopen them | Review after real use |
| Audits are planned one at a time, with no recurring schedule or reminder before the date. A missed audit shows as overdue in the list | When a user needs it |
| A recorded audit finding cannot be edited. A wrong one is handled by cancelling its NCR, and it stays on the report | Review after real use |
| `tenant_settings` is not built | When a user needs it |
| Dashboard figures are kept for 60 seconds per organisation, period and facility scope. Anything written to the audit log clears them at once on the same instance. Other changes, and changes on another instance, show within 60 seconds | With production work: use a shared cache if the figures must agree across instances |
| A report stops at 5,000 rows and says so, so a larger period must be split | When a user needs it |
| Key figures and charts show to anyone with `REPORT_VIEW`, as totals, even without the view permission for each kind of record. Lists and reports need that permission too | Review after real use |
| Material rejection adds quantities as recorded, without converting units | When a user needs it |
| No payment gateway yet. A platform administrator records each payment by hand, and no refund flow exists | When a gateway is chosen |
| An invoice is not a GST tax invoice: it shows a single GST line with no CGST and SGST split, place of supply or HSN or SAC code. An accountant must review it, and the seller's details must be set, before it goes to a customer | Before the first paid customer |
| A plan change has no proration. A different plan starts on payment and the unused time on the old one is lost | Review after real use |
| The starting user and facility limits, trial length, grace period and annual prices are placeholders, editable under Plans | Owner to confirm before launch |
| Which features each plan includes is an interpretation of the brief's table. Starter has no dashboards or reports | Owner to confirm before launch |
| The platform has one administrator level, listed in `platform_admins`. `SUPER_ADMIN` and `SUPPORT_ADMIN` are not separate roles | With the next admin work |
| The platform console lists 500 organisations, unpaid invoices first, and says when more exist. It has no search | When there are more |
| Attachments use local disk; S3 sits behind `FileStorage` and is not implemented | With production work |
| Lists show only active records, with no way to restore an archived one | When a user needs it |
| Facility, supplier, material and product pickers load the first 100 records | Add search when a tenant exceeds this |
| Facility sub-records (departments, lines, shifts), supplier contacts, material-to-product mappings, and new categories and units are not audited individually | Review after real use |

## Definition of done for each phase

Migrations, validation, error handling, audit events on critical actions, unit and integration tests,
tenant isolation tests for every new tenant-owned table, updated docs, and a report of open issues.
