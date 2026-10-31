# Platform administration

The Control Center is where the people who run QualityOS manage plans and customers. It lives in the main app at
`/platform`, with its own navigation. It is built in phases, and this page describes what works today.

| Phase | Scope | Status |
|---|---|---|
| 1 | Dashboard, plans, organisation search and detail | Built |
| 2 | Subscriptions, trials, invoices, payments | Not started |
| 3 | Feature catalogue, usage limits, analytics | Not started |
| 4 | Operators and roles, audit log, email log, jobs | Not started |
| 5 | Support console, announcements, settings | Not started |

A page joins the navigation only once it works.

## Bootstrap

The first operator cannot be created by the application, on purpose. Register the account normally, then run this as a
database superuser. Later operators are added under Operators by a Super Admin.

```sql
INSERT INTO platform_admins (user_id, role) SELECT id, 'SUPER_ADMIN' FROM users WHERE email = 'someone@example.com';
```

The same statement is the recovery process if no Super Admin can sign in. The application refuses to remove or demote
the last active Super Admin, so this only arises when that account is lost. For a deactivated row, run
`UPDATE platform_admins SET deactivated_at = NULL, role = 'SUPER_ADMIN' WHERE user_id = ...` instead.

## Operator roles

| Role | Can change | Can read |
|---|---|---|
| Super Admin | Everything, including operators | Everything |
| Billing Admin | Plans, features, invoices, payments, subscriptions | Everything except operators and the audit log |
| Support Admin | Suspend, reactivate, extend, end a trial, retry an email | Everything except operators and the audit log |
| Read-only Auditor | Nothing | Everything |

Roles come from `platform_admins` on every request. Removing someone takes effect on their next request.

## Re-authentication

Adding or changing an operator, saving or archiving a plan, raising an invoice, recording a payment, cancelling, and
suspending or reactivating an organisation need the password again, then allow 10 minutes. The time is held in the
database, and wrong passwords count towards the normal sign-in lockout.

## Audit log

Shows operator actions on the platform and on subscriptions, invoices, payments and organisation status. A customer's
own activity stays out of it. Rows cannot be changed or deleted by the application. Values whose names suggest secrets, tax numbers
or bank details are replaced with `[hidden]` when shown or exported, and each export is itself logged.

## Notifications, email and jobs

- Notifications lists billing notices only. Quality notifications stay private to each organisation.
- The email log shows one row per email. Only a notification that has used all its attempts can be retried.
- An invoice past its due date is chased once. Each expiry stage is sent once.
- Jobs shows every expected job, its latest result, failures in the last 24 hours, and its recent runs.

## Security

- Every `/api/v1/platform` path needs `PLATFORM_ADMIN`, which only a row in `platform_admins` gives.
- Functions that read across organisations check `platform_admins` themselves, so a mistake in the application
  cannot make them answer for an ordinary member.
- Anything about one organisation is read under that organisation's own tenant context. Row-level security stays on.
- The organisation page shows counts and event names, not the content of quality records.
- Not yet built: re-authentication for sensitive actions, and separate operator roles. Both are Phase 4.

## Plans

A plan's limits and features reach its subscribers at once. A new price reaches them on their next invoice, and issued
invoices never change. So a change to a plan with subscribers shows how many it reaches, and must give a reason.

Each saved state is kept in `subscription_plan_versions`, which the application cannot change or delete.

| Action | Rule |
|---|---|
| Create, copy | Starts out of sale and off the website |
| Deactivate | No new customers. Existing subscribers stay on it |
| Archive | Refused while anyone is on the plan, and for the trial plan. Cannot be undone |
| Website visibility | Hides a plan from the public price list. It can still be chosen in the app while offered |

Storage, monthly inspection, attachment and API limits are not offered yet, because nothing would enforce them. They
come with Phase 3. All amounts are in INR. The database refuses another currency until conversion exists.

## States

| Shown | Meaning |
|---|---|
| Trial | Trialling, and the period has not ended |
| Active | Paid, and the period has not ended |
| Grace | The period has ended, within the plan's grace days. Everything still works |
| Read-only | Grace has ended. Records can be read and exported, not changed |

A cancelled subscription keeps its state until its period ends. There is no separate stored `EXPIRED` or `CANCELLED`
state. Suspension does not exist yet (Phase 2).

## Dashboard figures

Calculated in the database from real records. Dates use Indian Standard Time. Period figures use the chosen dates, and
state counts are as of now. Charts group by calendar month, so a short period shows one or two bars.

| Figure | Definition |
|---|---|
| Contract at a moment | For each organisation, the latest paid invoice whose period covers that moment, unless the subscription was already cancelled |
| MRR | The sum of contracts now, without GST. An annual invoice counts as one twelfth. Trials, unpaid invoices and cancelled subscriptions count for nothing |
| ARR | MRR × 12 |
| Expired | Grace plus read-only |
| Outstanding invoices | The total of issued, unpaid invoices, with GST |
| Collected | Successful payments in the period, with GST. This is money received, unlike MRR |
| Collection rate | Of invoices issued in the period and not void, the share by value that is paid |
| Churn rate | Subscriptions cancelled in the period, as a share of those paying when it began |
| Became paying | Organisations registered that month that have ever paid an invoice |

A rate shows "No data" when there is nothing to measure. Churn counts a cancellation made in the period, even if the
paid time has not run out. An undone cancellation is not counted.

## Testing

```bash
cd backend && ./mvnw verify -Dit.test='PlatformControlCenterIT,SubscriptionIT' -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false
cd frontend && npx playwright test e2e/billing.spec.ts
```

## Billing operations

### Subscription lifecycle

The stored states stay `TRIALING` and `ACTIVE`. Everything else follows from dates, so access stays right even if no job has run. Each operation goes through `SubscriptionService` or `OrganizationService`.

| Operation | How it happens | Effect |
|---|---|---|
| Start | Registration | A trial on the default plan |
| Activate, renew, convert to paid, change plan | Raise an invoice, then record its payment | The plan and period change only when the invoice is paid |
| Extend trial, subscription or grace | Extend, with a reason | Moves the end date. Grace and read-only follow from it |
| End trial | End trial, with a reason | The trial ends now and grace starts |
| Cancel, resume | With a reason | Marks the subscription. Access continues to the end of the period |
| Suspend, restore | With a reason | Members lose access on their next request. No record is changed or deleted |

`Expired` means grace or read-only, and a read-only organisation keeps read and export access. Only an operator's suspension can be reactivated: a sign-up that expired has nobody left to return to it. An operator cannot suspend the organisation they are signed in to.

Every action writes an audit row in that organisation's trail, with its reason.

### Payments

Payments are recorded by hand against one invoice, and the amount must equal the invoice total, because partial payments are not part of the financial model. Failed attempts are kept and listed under the Failed filter. These are what needs following up.

A reference pays one invoice across the whole platform, ignoring case. Repeating it on the same invoice returns the first payment. The check reads existing payments, so two identical references saved at the same moment for different organisations could both pass it.

There is no gateway. `SubscriptionService.settle` already takes a provider name and treats a repeated reference as a retry, so a gateway's webhook can call it. It needs its own signature check when one is chosen.

### GST on invoices

**This is not reviewed by an accountant, and QualityOS does not claim GST compliance.**

From this release, an invoice stores its tax treatment when issued, by comparing the state codes opening the seller's and buyer's GSTINs:

| Seller and buyer | Invoice shows |
|---|---|
| Same state | CGST and SGST, half each |
| Different states | IGST |
| Either GSTIN missing | One GST line, and the treatment stays undetermined |

It does not handle exemptions, SEZ supplies, exports or an unregistered buyer's place of supply. Invoices issued earlier keep their single GST line. Set `BILLING_SELLER_GSTIN` and `BILLING_SAC_CODE` once an accountant has confirmed them.

## Features

A feature is something a plan can include. Keep this apart from roles: a plan decides what an organisation has bought, a role decides what a person may do, and both are checked.

Plans keep their `features` list, and any value sits beside it in `feature_values`. A value is a number, text or structured JSON. A blank number means unlimited.

**A feature only restricts anything once the product enforces it.** Enforcement is code, in `PlanAccess`, and today covers `NCR_CAPA`, `REPORTS` and `ADVANCED`. The Features page marks anything else "Listed only, not enforced": it appears on plans and the website, but leaving it out takes nothing away.

An inactive feature cannot be added to another plan. Plans that have it keep it, and an enforced feature stays enforced. On a plan with subscribers, a change needs a reason, reaches them at once, and joins the plan's history.

## Usage limits

| Limit | Counted | Checked when |
|---|---|---|
| Users | Active members plus pending invitations | Adding or inviting |
| Facilities | Facilities not archived | Adding |
| Storage | Bytes in attachments not removed | Uploading, before the file is stored |
| Attachments | Attachments not removed | Uploading, before the file is stored |
| Inspections a month | Inspections started since the 1st, in the organisation's time zone | Starting an inspection |

Blank means no limit, and every plan starts blank. Each check holds a lock on the organisation's subscription, so two requests cannot both take the last place. A refused inspection leaves the lot waiting: nothing is released or changed. A removed attachment stops counting, although its file is kept as evidence, so the disk holds more than the figure shows.

Usage is near a limit from 80%, and reaches it at 100%. A suggested plan is the next one offered that would fit what the organisation uses now. Daily history comes from a job that runs every six hours and replaces that day's row, so history starts on the day this is deployed.

API requests are not limited. There is no customer API to meter, so a limit would mean nothing.

## Analytics definitions

All amounts are in Indian rupees, without GST, and plans can only be priced in rupees. A period runs from the start of its first day to the end of its last, in India time.

| Figure | Definition |
|---|---|
| MRR | The same as the dashboard, at the end of the period: each organisation's latest paid invoice covering that moment, with annual invoices divided by 12. Cancelled subscriptions are left out |
| ARR | MRR times 12 |
| New customers | Organisations whose first paid invoice falls in the period |
| Average revenue per organisation | MRR divided by paying organisations at the end of the period |
| Customer churn | Organisations paying at the start and not at the end, as a share of those paying at the start |
| Revenue churn | Monthly revenue lost from organisations paying at the start, through leaving or moving to less, as a share of MRR at the start. Upgrades and new customers do not offset it |
| Trial conversion | Of organisations registered in the period, the share with a paid invoice by now. A recent period looks low because its trials have not finished |
| Revenue by plan | MRR by the plan name on each invoice |
| Subscription distribution | Organisations by current plan, including trials |
| Outstanding receivables | The total of all issued invoices not yet paid, today, whatever the period |
| Payment collection rate | The same as the dashboard: paid value as a share of invoices issued in the period, leaving out void ones |

A suspended organisation's contract still counts towards MRR until it ends or is cancelled.

