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

Administrators cannot be created by the application, on purpose. Register the account normally, then run this as a
database superuser:

```sql
INSERT INTO platform_admins (user_id) SELECT id FROM users WHERE email = 'someone@example.com';
```

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
