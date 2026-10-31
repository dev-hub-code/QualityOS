# API Contracts

Base path `/api/v1`. The running service is the source of truth for what exists: see `/v3/api-docs`.

**Implemented:** system info, health, authentication, organizations, users, roles, permissions and audit logs (Phases 1 and 2), and facilities, suppliers, materials, products, purchase orders, material receipts and attachments (Phase 3), inspection templates and inspections (Phase 4), notifications, invitations and account email (Phase 5), NCRs and CAPAs (Phase 6), and production, dispatch and traceability (Phase 7). Phase 8 onward is planned.

## Response envelope

Every response uses this shape. Implemented in `ApiResponse`.

```json
{ "success": true, "data": { }, "timestamp": "2026-10-31T04:00:00Z", "correlationId": "…" }
```

```json
{
  "success": false,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "Request validation failed",
    "violations": [{ "field": "name", "message": "must not be blank" }]
  },
  "timestamp": "2026-10-31T04:00:00Z",
  "correlationId": "…"
}
```

## Errors

| HTTP | Code | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Fields or parameters are invalid; see `violations` |
| 400 | `MALFORMED_REQUEST` | The body cannot be parsed |
| 401 | `UNAUTHENTICATED` | Token missing, invalid or expired; membership no longer active |
| 401 | `INVALID_CREDENTIALS`, `INVALID_REFRESH_TOKEN` | Sign-in failed; session has ended |
| 403 | `FORBIDDEN` | Permission missing, within the caller's tenant |
| 403 | `NO_ACTIVE_MEMBERSHIP` | Valid account, but no active organisation |
| 404 | `RESOURCE_NOT_FOUND` | Not found, **or owned by another tenant** |
| 405, 415 | `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE` | |
| 409 | `CONFLICT`, `CONCURRENT_MODIFICATION` | Duplicate data; stale `version` |
| 422 | `BUSINESS_RULE_VIOLATION` | Valid request refused by a rule, such as an illegal state transition |
| 500 | `INTERNAL_ERROR` | Details only in logs, found with the correlation ID |

Codes are stable. Clients should branch on `code`, not on `message`.

## Lists

`GET /suppliers?page=0&size=25&sort=name,asc&status=APPROVED&q=steel`. `size` has a maximum of 100, and sort
fields are allow-listed per endpoint. `data` is a `PageResponse`:

```json
{ "items": [], "page": 0, "size": 25, "totalItems": 0, "totalPages": 0 }
```

## Conventions

- JSON in `camelCase`. Timestamps are ISO-8601 UTC; decimals are strings, to preserve precision.
- Mutable records return `version`, and updates must send it back.
- State changes are explicit action endpoints (`POST /ncrs/{id}/close`), never a writable `status` field.
- `DELETE` archives a record. Records referenced by quality history cannot be deleted.
- Requests never carry a tenant ID.

## Authentication

| Method and path | Purpose |
|---|---|
| `POST /auth/register` | Create a user and organisation |
| `POST /auth/login` | Returns tokens and memberships |
| `POST /auth/refresh` | Rotates the refresh token. Reusing an old one more than 10 seconds later revokes the whole family; within 10 seconds it is treated as a parallel-request race |
| `POST /auth/logout` | Revokes the token family |
| `POST /auth/switch-tenant` | New token for another verified membership. An addition to the brief |
| `POST /auth/change-password` | Requires the current password and revokes all sessions. An addition to the brief |
| `GET /auth/me` | User, current organisation, permissions and memberships. An addition to the brief |

Access token: RS256, 15 minutes, with claims `sub` and `tid` (tenant). Membership and permissions are
loaded on the server for every request, so changes apply at once rather than when the token expires.

Browsers use the Next.js routes under `/api/auth/*`, which hold tokens in httpOnly cookies. The `/api/v1/*`
proxy refuses token-carrying endpoints, and refuses writes from another origin.

## Planned resources

| Phase | Paths |
|---|---|
| 2 (built) | `/organizations`, `/users` (plus `/{membershipId}/roles`, `/activate`, `/deactivate`), `/roles`, `/permissions`, `/audit-logs` |
| 3 (built) | `/facilities`, `/suppliers`, `/products`, `/materials`, `/purchase-orders`, `/material-receipts` |
| 4 (built) | `/inspection-templates` (plus `/{id}/versions`, `/{id}/publish`), `/inspections` (plus `/{id}/execute`, `/submit`, `/approve`, `/send-back`, `/disposition`, `/cancel`), `/material-receipts/awaiting-inspection` |
| 5 (built) | `/auth/password-reset/{request,confirm}`, `/auth/verify-email`, `/auth/resend-verification`, `/auth/verification-status`, `/auth/invitations/{preview,accept}`, `/invitations` (plus `/{id}/resend`, `/{id}/revoke`), `/notifications` (plus `/unread-count`, `/{id}/read`, `/read-all`, `/preferences`). `POST /users` is removed: people join by invitation |
| 6 (built) | `/ncrs` (plus `/{id}/assign`, `/containment`, `/investigation`, `/request-verification`, `/send-back`, `/close`, `/reject`, `/cancel`), `/capas` (plus `/{id}/start-analysis`, `/analysis`, `/finish-analysis`, `/actions`, `/actions/{actionId}/complete`, `/start-implementation`, `/submit-verification`, `/verify`, `/close`, `/cancel`), `/defect-categories`, `/quality/assignees` |
| 7 (built) | `/production-batches` (plus `/{id}/consume`, `/{id}/consumptions/{consumptionId}/reverse`, `/complete`, `/serials`), `/customers`, `/dispatches` (plus `/{id}/reverse` for a dispatch that never happened, `/{id}/reversals`, `/{id}/reversal-summary`), `/dispatch-reversals`, `/material-corrections` (plus `/{id}/submit`, `/approve`, `/reject`, `/cancel`), `/batches/{id}/correction-history`, `/traceability/search`, `/traceability/batches/{batchNumber}`, `/traceability/products/{productId}`, `/material-receipts/released-lots`. `POST /inspections` now takes `productionBatchId` or `returnItemId` in place of `receiptItemId` |
| 8 | `/instruments`, `/calibrations`, `/audits` |
| 9 | `/analytics/dashboard`, `/analytics/supplier-quality`, `/analytics/inspection-trends`, `/analytics/defect-analysis`, `/reports/{type}` |
| 10 | `/subscription`, `/platform/plans` |

Contracts are finalised in OpenAPI as each phase is built.

## Email verification

| Endpoint | Access | Result |
|---|---|---|
| `POST /auth/register` | Public | `202` with `verificationRequired: true` and no session. The answer is the same for an address already in use. With verification off: `201` and a session |
| `POST /auth/verify-email` | Public | `204`, or `422` with one message for invalid, expired and used links |
| `POST /auth/resend-verification` | Public | Always `202` |
| `GET /auth/verification-status` | Any session, including a pending one | `emailVerified`, `verificationRequired`. Not public |

The right password on an unconfirmed account returns `202` with a restricted access token: no tenant, no refresh token,
and usable only on `verification-status`. Anything else returns `403 EMAIL_NOT_VERIFIED`. A wrong password gets the
usual `401`. Registration never issues a token, because that would show whether an address was already taken. Links are stored hashed, work once, and replace earlier links. Resends are limited to one a minute and five an
hour per account, on top of the IP limit.

Settings are under `app.registration`. The `prod` profile refuses to start unless verification is required and
unverified sign-in is off. Local Docker Compose enforces it too. Integration tests turn it off, except `EmailVerificationIT`.

`PUT /auth/me/email-language` takes `language` (`en`, `hi`, `mr`, or null for the organisation default) and returns `204`.

## Dispatch cancellation and customer returns

| Action | Endpoint | Rule |
|---|---|---|
| Cancel a dispatch that never happened | `POST /dispatches/{id}/reverse` | Refused once a shipment is confirmed or any return exists. Restocks at once, because the goods never left |
| Confirm goods left | `POST /dispatches/{id}/shipment` | Once only, and never on a cancelled dispatch |
| Record a return | `POST /dispatches/{id}/reversals` | Needs a confirmed shipment. Stock returns only when an inspection accepts the goods |

Dispatches, customers and return lines stay on the trace after a full return, so `affectedCustomers` includes everyone
a shipment reached. Only a dispatch cancelled through `/reverse` drops out, because those goods never left.

A return sent with `expected: true` starts as `EXPECTED`: its quantity is held against the dispatch, but it cannot be
inspected. `POST /dispatch-reversals/{id}/receive` records arrival, and `POST /dispatch-reversals/{id}/cancel` releases the
quantity if the goods never come.

`GET /traceability/batches/{number}` also returns `recall` (dispatched, returned and outstanding per dispatch) and
`unconfirmedCustomers`. `GET /traceability/serials/{serial}` lists every customer one unit reached.

`GET /traceability/serials` searches units. `q` is matched in any case, in the column named by `field` (`SERIAL`, `PRODUCT`,
`BATCH`, `CUSTOMER`, `DISPATCH`), or all of them. It also takes `facilityId`, `madeFrom`, `madeTo`, `dispatchedFrom`,
`dispatchedTo`, `page`, `size` and `sort`, and refuses a search with no filter at all.

`PUT /users/{membershipId}/facilities` limits a member to some facilities. An empty list means all of them. Records outside
a member's facilities answer 404.

| Recalls | Permission |
|---|---|
| `GET /recalls`, `GET /recalls/{id}`, `/customers`, `/contact-history`, `/dashboard`, `/report`, `/report.pdf` | `RECALL_VIEW` |
| `POST /recalls`, `PUT /recalls/{id}/customers/{customerId}`, `POST .../contact-attempts`, `POST .../status`, `POST /recalls/{id}/acknowledgements`, `POST /recalls/{id}/request-closure` | `RECALL_MANAGE` |
| `POST /recalls/{id}/customers/{customerId}/exception`, `POST /recalls/{id}/send-back`, `POST /recalls/{id}/close` | `RECALL_CLOSE` |

A sent email is an attempt with the outcome `NO_REPLY`. Only `REACHED` moves a customer to `CONTACTED`, and only an
acknowledgement allows `RESOLVED`. Closing needs every customer resolved or covered by an exception, any linked CAPA closed,
and a second person.

| Calibration | Permission |
|---|---|
| `GET /instruments`, `GET /instruments/{id}`, `/calibrations`, `/usage`, `/qr.svg` | `CALIBRATION_VIEW` |
| `POST /instruments`, `PUT /instruments/{id}`, `POST /instruments/{id}/status`, `POST /instruments/{id}/calibrations` | `CALIBRATION_MANAGE` |
| `GET /instruments/usable`, `POST /inspections/{id}/instruments` | `INSPECTION_EXECUTE` |

| Audits | Permission |
|---|---|
| `GET /audit-checklists`, `GET /audit-checklists/{id}`, `GET /audits`, `GET /audits/{id}`, `/report`, `/report.pdf` | `AUDIT_VIEW` |
| `POST`, `PUT`, `DELETE /audit-checklists`; `POST /audits`, `PUT /audits/{id}`, `PUT .../items/{itemId}`, `POST .../start`, `/findings`, `/findings/{findingId}/ncr`, `/complete`, `/request-closure`, `/cancel` | `AUDIT_MANAGE` |
| `POST /audits/{id}/send-back`, `POST /audits/{id}/close` | `AUDIT_CLOSE` |

A major or minor nonconformity raises an NCR with the finding. An audit closes once its NCRs are finished, and needs a second
person. The QR code holds only a link, which asks whoever scans it to sign in.

## Analytics and reports

All need `REPORT_VIEW`. `from` and `to` are dates in the organisation's time zone. They default to the last 90 days and
may span two years at most. `facilityId` narrows the result; a facility the caller may not see is a 404.

| Method | Path | Result |
|---|---|---|
| GET | `/api/v1/analytics/overview` | Key figures, chart series, pending approvals, recent activity |
| GET | `/api/v1/reports/{slug}` | Report as JSON: columns, up to 5,000 rows, `truncated` |
| GET | `/api/v1/reports/{slug}.pdf` | The same report as a PDF, audited as `REPORT_EXPORTED` |
| GET | `/api/v1/reports/{slug}.xlsx` | The same report as a workbook, audited as `REPORT_EXPORTED` |

Slugs: `inspection`, `supplier-quality`, `material-rejection`, `ncr`, `capa`, `calibration`, `audit`,
`batch-traceability`. Each also needs the view permission for what it lists, or the call is a 403.

How each figure is defined is in [ANALYTICS.md](ANALYTICS.md).

## Subscription

Reading `/api/v1/subscription` needs only membership. Everything else here needs `SUBSCRIPTION_MANAGE`.

| Method | Path | Result |
|---|---|---|
| GET | `/api/v1/subscription` | Plan, state (`TRIAL`, `ACTIVE`, `GRACE`, `READ_ONLY`), usage and limits |
| GET | `/api/v1/subscription/plans` | Plans on offer |
| POST | `/api/v1/subscription/plan` | Raises an invoice, voiding any open one. Takes effect when paid. Also how to renew |
| POST | `/api/v1/subscription/cancel`, `/resume` | Cancelling runs to the end of the period |
| GET | `/api/v1/subscription/invoices`, `/invoices/{id}/pdf`, `/payments` | Billing history |

`GET /api/v1/public/plans` needs no sign-in. It serves the website's price list: plans on offer, without ids, tax or
grace settings.

## Platform administration

All need `PLATFORM_ADMIN`, which only a row in `platform_admins` gives. The tenant id in these paths is an operator's
choice of organisation, and the work runs under that organisation's tenant context.

| Method | Path | Result |
|---|---|---|
| GET, PUT | `/api/v1/platform/plans`, `/plans/{id}` | Prices, limits, features, trial and grace days. A plan with subscribers needs `reason` |
| POST | `/api/v1/platform/plans` | New plan from `code` and `name`, out of sale |
| POST | `/api/v1/platform/plans/{id}/duplicate` | Copy of a plan's terms, out of sale |
| POST | `/api/v1/platform/plans/{id}/active` | Offers or withdraws a plan. Needs `version` and `reason` |
| POST | `/api/v1/platform/plans/{id}/archive` | Needs `version` and `reason`. Refused while anyone is on the plan |
| GET | `/api/v1/platform/plans/{id}/impact`, `/versions` | Subscriber count, and the plan's saved history |
| GET | `/api/v1/platform/dashboard` | `from`, `to`. Figures are defined in [PLATFORM_ADMIN.md](PLATFORM_ADMIN.md) |
| GET | `/api/v1/platform/organisations` | `q` (name, email, GSTIN), `plan`, `state`, `unpaid`, `registeredFrom`, `registeredTo`, `sort` (`NAME`, `NEWEST`, `ENDING`), `page`, `size` up to 100. Unpaid first by default |
| GET | `/api/v1/platform/organisations/export` | The same filters as CSV, up to 5,000 rows. Leaves an audit event |
| GET | `/api/v1/platform/organisations/{id}` | Profile, subscription, usage counts, invoices, payments, recent event names |
| POST | `/api/v1/platform/organisations/{id}/invoices` | Needs `planId`, `cycle` and `reason`. `amount` is optional and defaults to the list price |
| POST | `/api/v1/platform/organisations/{id}/invoices/{invoiceId}/payments` | Needs `reference`, `amount`, `failed`, `method` and `paidOn`, with optional `notes`. The amount must equal the invoice total, and a reference pays one invoice across the platform |
| POST | `/api/v1/platform/organisations/{id}/extend` | Adds days. Needs `days` and `reason` |
| POST | `/api/v1/platform/organisations/{id}/trial/end` | Ends a running trial, so grace starts now. Needs `reason` |
| POST | `/api/v1/platform/organisations/{id}/cancel`, `/resume` | Needs `reason` |
| POST | `/api/v1/platform/organisations/{id}/suspend`, `/reactivate` | Needs `reason`. 204. Records are untouched |
| GET | `/api/v1/platform/organisations/{id}/invoices/{invoiceId}`, `/pdf` | One invoice with its GST detail, or its PDF |
| GET | `/api/v1/platform/invoices` | `q`, `status`, `overdue`, `from`, `to`, `min`, `max`, `page`, `size`. Includes paid and outstanding |
| GET | `/api/v1/platform/invoices/export` | The same filters as CSV, up to 5,000 rows. Leaves an audit event |
| GET | `/api/v1/platform/payments` | `q` (reference, invoice, organisation), `status`, `from`, `to`, `page`, `size` |
| GET | `/api/v1/platform/trials/summary` | Trials ending in 3 and 7 days, converted this month, conversion rate |
| GET | `/api/v1/platform/analytics`, `/analytics/export` | `from`, `to`. Business figures, or as CSV. The export leaves an audit event |
| GET | `/api/v1/platform/usage`, `/usage/export` | `q`, `alert` (`NEAR`, `REACHED`), `page`, `size`. The export runs to 5,000 rows and leaves an audit event |
| GET | `/api/v1/platform/organisations/{id}/usage/history` | `days`, default 30, up to 366 |
| GET, POST | `/api/v1/platform/features` | The catalogue, with `enforced` and how many organisations have each. POST needs `code`, `name`, `valueType`, `defaultEnabled` |
| PUT | `/api/v1/platform/features/{code}` | `name`, `description`, `defaultEnabled`, `version` |
| POST | `/api/v1/platform/features/{code}/active` | `active`, `version`, `reason` |
| POST | `/api/v1/platform/features/{code}/plans` | `planId`, `included`, `value`, `version`, and `reason` when the plan has subscribers |
| GET | `/api/v1/platform/features/{code}/organisations` | `page`, `size` |

To make someone a platform administrator, an operator runs this as a database superuser:

```sql
INSERT INTO platform_admins (user_id) SELECT id FROM users WHERE email = 'someone@example.com';
```

## Platform operations

All under `/api/v1/platform`, and all need `PLATFORM_ADMIN`. A critical action refused with `REAUTHENTICATION_REQUIRED`
may be sent again after `POST /reauthenticate`.

| Method and path | Also needs | Purpose |
|---|---|---|
| `POST /reauthenticate` | | Confirm the password for 10 minutes. A wrong one gives `REAUTHENTICATION_FAILED` |
| `GET /operators` | `PLATFORM_AUDIT_VIEW` | List operators |
| `POST /operators` | `PLATFORM_OPERATOR_MANAGE` | Add an operator by email, role and reason |
| `PUT /operators/{userId}` | `PLATFORM_OPERATOR_MANAGE` | Change role or active state, with a reason |
| `GET /audit-logs`, `GET /audit-logs/export` | `PLATFORM_AUDIT_VIEW` | Filter by `q`, `action`, `entityType`, `from`, `to` |
| `GET /email-logs` | | Filter by `q`, `status`, `retryable` |
| `POST /organisations/{tenantId}/notifications/{notificationId}/retry-email` | `PLATFORM_CUSTOMER_MANAGE` | Send a given-up email again |
| `GET /notifications` | | Billing notices, by `type` |
| `GET /jobs`, `GET /jobs/{job}/runs` | | Job health and recent runs |
