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
| 422 | `RECALL_HOLD` | A dispatch includes goods under an open recall. Send `recallOverrideReason` (needs `RECALL_MANAGE`) to dispatch them anyway |
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
| `PUT`, `DELETE /suppliers/categories/{id}` | Rename or delete. Delete is refused with `409` while any supplier holds it |
| `PUT`, `DELETE /product-categories/{id}` | Rename or delete. Delete is refused with `409` while any material or product holds it |
| `GET /subscription/payment-gateway` | Whether online payment is available, and in which mode |
| `POST /subscription/invoices/{id}/checkout` | Takes `phone`. Opens or continues a Cashfree order for the invoice's own total |
| `POST /subscription/gateway-orders/{orderId}/confirm` | Asks Cashfree what happened and applies it. Returns `ACTIVE`, `PAID` or `NEEDS_REVIEW` |
| `POST /public/webhooks/cashfree` | Cashfree only, checked by signature. Reach it through the web app at `/api/webhooks/cashfree` |
| `GET /auth/me` | User, current organisation, whether it has a subscription, permissions and memberships. An addition to the brief |

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
| 7 (built) | `/production-batches` (plus `/{id}/consume`, `/{id}/consumptions/{consumptionId}/reverse`, `/complete`, `/serials`), `/customers`, `/dispatches` (plus `/{id}/reverse` for a dispatch that never happened, `/{id}/reversals`, `/{id}/reversal-summary`), `/dispatch-reversals`, `/material-corrections` (plus `/{id}/submit`, `/approve`, `/reject`, `/cancel`), `/batches/{id}/correction-history`, `/traceability/search`, `/traceability/batches/{batchNumber}`, `/traceability/products/{productId}`, `/material-receipts/released-lots`. `POST /{id}/consume` takes an optional `uomId`: the quantity is converted to the lot's or batch's own unit, and what was typed is kept on the entry. `POST /inspections` now takes `productionBatchId` or `returnItemId` in place of `receiptItemId`. `templateId` is optional: without it the one fitting template is used. `GET /inspections/applicable-templates?receiptItemId=|productionBatchId=|returnItemId=` lists the fitting templates and the suggested one |
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

## Organisation settings

| Endpoint | Access | Result |
|---|---|---|
| `GET /organizations/current/settings` | Any member | `singlePersonReview`, `selfReviewInEffect`, `activeMembers`, `capaRequiredFrom`, `effectivenessRequiredFrom`, `version` (null until first saved) |
| `PUT /organizations/current/settings` | `TENANT_MANAGE` | Takes every rule (`singlePersonReview`, `capaRequiredFrom`, `effectivenessRequiredFrom`) and the `version` last read. A missing rule is `400`, a stale version `409`. Audited as `TENANT_SETTINGS_UPDATED` with before and after |

`selfReviewInEffect` is true while `singlePersonReview` is on and the organisation has one active member. Then that
person may approve or send back their own inspection, close an NCR whose verification they requested, and verify or
close a CAPA they own. Each of those needs a note, and is recorded in the audit log as `SELF_REVIEW` with the step
(`INSPECTION_APPROVE`, `INSPECTION_SEND_BACK`, `NCR_CLOSE`, `CAPA_VERIFY`, `CAPA_CLOSE`) and the reason. Once a second
member is active, the usual rule returns without anyone changing the setting.

`capaRequiredFrom` and `effectivenessRequiredFrom` each name the least severe NCR a rule covers: `MINOR` (every NCR),
`MAJOR` (critical and major, the default), `CRITICAL`, or `NEVER`. The first decides which NCRs need a closed CAPA
before `request-verification`, checked at that moment. The second forces `effectivenessRequired` on when a CAPA is
raised on an NCR it covers, and the CAPA keeps that flag. `GET /ncrs/{id}` returns `capaRequired` and
`effectivenessRequired`, worked out for that NCR under the current rules.

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
| `GET /instruments`, `GET /instruments/{id}`, `/calibrations`, `/usage`, `/impact-reviews`, `/qr.svg` | `CALIBRATION_VIEW` |
| `POST /instruments`, `PUT /instruments/{id}`, `POST /instruments/{id}/status`, `POST /instruments/{id}/calibrations`, `POST /instruments/{id}/impact-reviews/{reviewId}/decision` | `CALIBRATION_MANAGE` |
| `GET /instruments/usable`, `POST /inspections/{id}/instruments` | `INSPECTION_EXECUTE` |

A failed calibration lists each inspection that used the instrument since its last good calibration, and raises one MAJOR
NCR (source `CALIBRATION`) for them. With nothing in doubt, no NCR is raised. `GET /instruments/{id}/impact-reviews` groups
the reviews by failed calibration, newest first, with the NCR and the number still pending. A decision takes `decision`
(`RESULT_STANDS` or `REINSPECT`) and a `note`, is made once (`422` after that), and is audited as
`CALIBRATION_IMPACT_DECIDED`. `REINSPECT` holds whatever the inspection released: a lot goes back to awaiting
inspection, and a batch (also for an accepted return) goes `ON_HOLD`, until a new inspection releases it. Stock already
used or dispatched stays where it is. It is `422` on an inspection that is not completed. An NCR raised by a calibration
cannot be cancelled, only rejected, and cannot close while any of its inspections has no decision. Each instrument on an
inspection carries `impactReview`: `PENDING`, `RESULT_STANDS`, `REINSPECT` or null.

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

## Platform console

| Method and path | Also needs | Purpose |
|---|---|---|
| `GET /api/v1/platform-notices` | Any member, not `PLATFORM_ADMIN` | Maintenance state, support email and today's announcements |
| `GET /api/v1/platform/settings` | | Read settings |
| `PUT /api/v1/platform/settings` | `PLATFORM_SETTINGS_MANAGE`, password | Save, with `version` and `reason` |
| `GET /api/v1/platform/announcements` | | List |
| `POST /api/v1/platform/announcements`, `PUT .../{id}` | `PLATFORM_CUSTOMER_MANAGE` | Create or save, with `version` and `reason` |
| `GET /api/v1/platform/support/tickets` | | Filter by `tenantId`, `status` |
| `POST /api/v1/platform/support/tickets` | `PLATFORM_CUSTOMER_MANAGE` | Open with `tenantId`, `subject`, `note` |
| `PUT /api/v1/platform/support/tickets/{id}/status` | `PLATFORM_CUSTOMER_MANAGE` | Change status, with `version` |
| `GET`, `POST /api/v1/platform/support/tickets/{id}/notes` | `POST` needs `PLATFORM_CUSTOMER_MANAGE` | Read or add notes |
| `GET /api/v1/platform/support/organisations/{tenantId}/errors` | | Failed emails in the last 30 days |

## Recall hold on dispatch

A batch is held while a recall that is not closed (`OPEN` or `PENDING_CLOSURE`) started from it, or from any batch it
was made from. `POST /dispatches` with a held batch returns `422 RECALL_HOLD`, naming the batches and recalls.

`recallOverrideReason` on the same request lets the goods out. The caller needs `RECALL_MANAGE`, or the answer is
`403`. The dispatch is audited as `RECALL_HOLD_OVERRIDDEN` with the recalls and the reason, and its customer is added to
each recall's contact list in the same transaction. Closing the recall releases the hold.


## Manufacturing

All under `/api/v1/manufacturing`. Reading needs `MANUFACTURING_VIEW`. The `MANUFACTURING` plan feature carries
every permission below; after a downgrade only `MANUFACTURING_VIEW` stays. See [MANUFACTURING.md](MANUFACTURING.md).

| Method and path | Also needs | Purpose |
|---|---|---|
| `GET /settings`, `PUT /settings` | `PUT`: `MANUFACTURING_MANAGE` | `manufacturingModel` (DISCRETE, PROCESS, HYBRID), `separateApprover`, and optionally `workOrderRequiresBom` (default true), `workOrderRequiresRouting`, `reserveOnRelease`, `startRequiresMaterials` and `allowOverIssue` (all default false), `operationsInSequence` (default true), `operationRequiresMachine` (default false) and `overReportPercent` (0 to 100, default 0); left out, those keep their value. A model that products still use cannot be dropped |
| `GET /products/{productId}/settings`, `PUT ...` | `PUT`: `MANUFACTURING_MANAGE` | How one product is made. `manufacturingModel` is null while a hybrid organisation has not chosen |
| `GET /work-centers`, `POST /work-centers` | `POST`: `MANUFACTURING_MANAGE` | Filter by `q`, `facilityId`. Limited members see their facilities only |
| `GET /work-centers/{id}`, `PUT ...`, `DELETE ...` | `MANUFACTURING_MANAGE` to change | Detail with machines. `DELETE` archives, and is refused while a machine is not inactive |
| `GET /machines`, `POST /machines` | `POST`: `MANUFACTURING_MANAGE` | Filter by `q`, `workCenterId`, `status` |
| `GET /machines/{id}`, `PUT ...` | `PUT`: `MANUFACTURING_MANAGE` | Detail with the last 100 status changes |
| `POST /machines/{id}/status` | `MANUFACTURING_MANAGE` | `status` and `reason`. Only allowed transitions; a reason for maintenance, breakdown and inactive |
| `GET /boms`, `POST /boms` | `POST`: `BOM_MANAGE` | Filter by `q` (product), `productId`, `status`. `POST` takes `productId`, `changeNote`, `baseQuantity`, and `copyFromId` or `fromProductMaterials` |
| `GET /boms/{id}`, `PUT ...`, `DELETE ...` | `BOM_MANAGE` to change | `DELETE` discards a draft that was never submitted |
| `POST /boms/{id}/lines`, `PUT .../lines/{lineId}`, `DELETE ...` | `BOM_MANAGE` | One of `materialId` or `componentProductId`, `quantity`, `uomId`, `scrapPercent`, `scales`, `notes`. Draft only |
| `POST /boms/{id}/submit` | `BOM_MANAGE` | Needs at least one line |
| `POST /boms/{id}/send-back`, `/approve`, `/retire` | `BOM_APPROVE` | `approve` takes optional `effectiveFrom` (today or later) and `note` |
| `GET /boms/{id}/explosion?quantity=&on=` | | Every level, in each component's unit, with scrap, and leaf totals |
| `/recipes/...` | `RECIPE_MANAGE`, `RECIPE_APPROVE` | As `/boms`, plus `POST`/`DELETE /recipes/{id}/parameters` and `GET /recipes/{id}/scaled?batchSize=` |
| `/routings/...` | `ROUTING_MANAGE`, `ROUTING_APPROVE` | As `/boms` without lines, with `POST`/`PUT`/`DELETE /routings/{id}/operations[/{operationId}]` and `.../operations/{operationId}/parameters` |

Refusals are `422` with a message: a BOM for a process product, a circular structure, a unit that does not convert, a
change to anything that is not a draft, approval by the submitter under `separateApprover`, or an effective date before
today or before the current revision's. A second revision in progress for the same product is `409`.

### Production plans and work orders

Reading needs `WORK_ORDER_VIEW`, for plans as well as work orders. Members limited to some facilities see and change
only those facilities' plans and orders; anything else is `404`.

| Method and path | Also needs | Purpose |
|---|---|---|
| `GET /production-plans`, `POST /production-plans` | `POST`: `PRODUCTION_PLAN_MANAGE` | Filter by `q`, `status`, `facilityId`. `POST` takes `name`, `facilityId`, `periodStart`, `periodEnd`, `notes`; the number is `PP-yyyy-nnnnn` |
| `GET /production-plans/{id}`, `PUT ...` | `PUT`: `PRODUCTION_PLAN_MANAGE` | Detail with lines; each line has `orderedQuantity` (its work orders, cancelled ones aside), `remainingQuantity`, `overOrdered` and its work orders. `PUT` on drafts only; the facility cannot change |
| `POST /production-plans/{id}/lines`, `PUT .../lines/{lineId}`, `DELETE ...` | `PRODUCTION_PLAN_MANAGE` | `productId`, `quantity`, `dueDate` within the period, `notes`. Draft only |
| `POST /production-plans/{id}/approve` | `PRODUCTION_PLAN_APPROVE` | Needs a line |
| `POST /production-plans/{id}/close` | `PRODUCTION_PLAN_APPROVE` | Optional `note`. Refused while any of its work orders is open |
| `POST /production-plans/{id}/cancel` | `PRODUCTION_PLAN_APPROVE` | `reason`. Refused if any work order raised from it is not cancelled |
| `GET /work-orders`, `POST /work-orders` | `POST`: `WORK_ORDER_MANAGE` | Filter by `q` (number or product), `status`, `facilityId`, `productId`, `planId`. `POST` takes `productId`, `facilityId`, `productionLineId`, `quantity` (in the product's unit), `plannedStart`, `plannedEnd`, `priority` (LOW, NORMAL, HIGH, URGENT), `notes`, `planLineId`. From a plan line: the plan must be approved, and product and facility must match it |
| `GET /work-orders/{id}`, `PUT ...` | `PUT`: `WORK_ORDER_MANAGE` | Detail: the order, `definition` (the BOM or recipe and routing a release would use or did use, and `problems` that would stop it), `materials` and `operations` frozen at release, and the status `history`. `PUT` on drafts only |
| `POST /work-orders/{id}/submit` | `WORK_ORDER_MANAGE` | DRAFT → SUBMITTED |
| `POST /work-orders/{id}/send-back` | `WORK_ORDER_APPROVE` | `note`. SUBMITTED → DRAFT |
| `POST /work-orders/{id}/approve` | `WORK_ORDER_APPROVE` | Optional `note`. SUBMITTED → APPROVED; the submitter cannot approve under `separateApprover`, except as a recorded self-review |
| `POST /work-orders/{id}/release` | `WORK_ORDER_RELEASE` | APPROVED → RELEASED. Freezes the BOM or recipe and routing in effect on the planned start and writes the materials and operations |
| `POST /work-orders/{id}/start` | `WORK_ORDER_RELEASE` | RELEASED → IN_PROGRESS. Opens the production batch (`productionBatchId`, `productionBatchNumber`) |
| `POST /work-orders/{id}/hold`, `/resume` | `WORK_ORDER_RELEASE` | `hold` takes `reason`, from APPROVED, RELEASED or IN_PROGRESS; `resume` returns to that status |
| `POST /work-orders/{id}/cancel` | `WORK_ORDER_MANAGE` before approval, `WORK_ORDER_APPROVE` after | `reason`. Never once production has started. Releases the order's reservations |
| `GET /work-orders/{id}/materials` | | Per line: `required`, `issued`, `reserved`, `outstanding`, `available`, `shortage`, and `sources` (free lots or released batches in the facility); `reservations`, `consumptions` (what went into the batch and still stands), `movements`, and `shortages` as sentences |
| `POST /work-orders/{id}/reservations` | `WORK_ORDER_MATERIAL_RESERVE` | `lineNumber`, `lotId`, `quantity` (in the line's unit). Released or in progress; materials only; at most what the line still needs |
| `POST /work-orders/{id}/reservations/auto` | `WORK_ORDER_MATERIAL_RESERVE` | Reserves what every line still needs from the oldest free lots, as far as they go |
| `POST /work-orders/{id}/reservations/{reservationId}/release` | `WORK_ORDER_MATERIAL_RESERVE` | Optional `reason`. Gives back what the reservation still holds |
| `POST /work-orders/{id}/issues` | `WORK_ORDER_MATERIAL_ISSUE` | `lineNumber`, `lotId` or `sourceBatchId`, `quantity` (in the line's unit). In progress only; the order's reservations on the lot are used first; more than needed is refused unless `allowOverIssue` |
| `POST /work-orders/{id}/returns` | `WORK_ORDER_MATERIAL_ISSUE` | `consumptionId`, `quantity` (in the source's unit), `reason` |

A step from the wrong status is `422` naming the order's status; a concurrent change to the same order is `409`.
Nothing reaches COMPLETED or PARTIALLY_COMPLETED yet: that comes with finished-goods receipt in phase 6.

Reserve, automatic reserve, issue and return take an optional `Idempotency-Key` header (1 to 100 characters). A key
already used on the same work order returns the current materials without moving stock again. With
`startRequiresMaterials`, `start` is refused with the shortfalls; otherwise a short start is recorded in the history.
Lots from `GET /material-receipts/released-lots` and elsewhere now carry `reservedQuantity` and `facilityId`; ordinary
consumption takes only `remainingQuantity` less `reservedQuantity`.

Work order operations now also carry `statusReason`, `operatorName`, `machineCode`, `input`, `goodQuantity`,
`rejectedQuantity`, `scrapQuantity` and `waiting`.

#### Shop floor: `/api/v1/manufacturing/operations`

Reading needs `WORK_ORDER_VIEW`. Quantities are in the work order's unit. Every change returns the operation's detail.

| Endpoint | Permission | Notes |
|---|---|---|
| `GET /operations` | | Open operations of orders in progress or on hold, running first. `status` (repeatable), `facilityId`, `workCenterId`, `mine`, `q` (order number or product), paged |
| `GET /operations/{id}` | | `operation` (with `input`, `processed`, `goodQuantity`, `rejectedQuantity`, `scrapQuantity`, `reworkQuantity`, `waiting`), `instructions`, planned and running times, `parameters`, `reports`, `measurements`, `events`, `machines` at its work centre, `operators` who may run it, `batchStatus`, and its quality `gates` |
| `POST /operations/{id}/start` | `PRODUCTION_EXECUTE` | Optional `machineId`. Ready operations of orders in progress |
| `POST /operations/{id}/pause` | `PRODUCTION_EXECUTE` | `category` (stop reason), optional `reason` |
| `POST /operations/{id}/resume` | `PRODUCTION_EXECUTE` | |
| `POST /operations/{id}/reports` | `PRODUCTION_EXECUTE` | `quantity`, `goodQuantity`, `rejectedQuantity`, `scrapQuantity` (must add up to `quantity`), optional `reworkQuantity` (within rejected) and `comment`. Accepts `Idempotency-Key` |
| `POST /operations/{id}/measurements` | `PRODUCTION_EXECUTE` | `parameterCode`, `value`, optional `comment`. Accepts `Idempotency-Key` |
| `POST /operations/{id}/complete` | `PRODUCTION_EXECUTE` | Optional `setupMinutes` and `reason`; the reason is required when less was processed than came in |
| `POST /operations/{id}/comments` | `PRODUCTION_EXECUTE` | `comment` |
| `POST /operations/{id}/assign` | `PRODUCTION_SUPERVISE` | `operatorId`, `machineId`; either may be null to clear |
| `POST /operations/{id}/block` | `PRODUCTION_SUPERVISE` | `category`, `reason` |
| `POST /operations/{id}/unblock` | `PRODUCTION_SUPERVISE` | |
| `POST /operations/{id}/skip` | `PRODUCTION_SUPERVISE` | `reason`. Only when nothing was reported on it |
| `POST /operations/{id}/reports/{reportId}/reverse` | `PRODUCTION_SUPERVISE` | `reason`. Once per report, while the operation is open |

Since phase 5, `start` is refused while a mandatory start gate of the operation has not passed, and `complete` while a
mandatory in-process or completion gate has not. While the batch is on hold or rejected, `start`, `resume`, `reports`,
`measurements` and `complete` are refused. All are `422` with a message naming what is missing.

An operation assigned to someone else is refused with 403 unless the caller holds `PRODUCTION_SUPERVISE`. Photographs
use `/api/v1/attachments` with `entityType=WORK_ORDER_OPERATION`: viewing needs `WORK_ORDER_VIEW`, adding needs
`PRODUCTION_EXECUTE`.

#### Quality checkpoints: `/api/v1/manufacturing/quality-checkpoints`

Reading needs `MANUFACTURING_VIEW`; changes need `QUALITY_CHECKPOINT_MANAGE`.

| Endpoint | Notes |
|---|---|
| `GET /quality-checkpoints` | All checkpoints, by stage and name. `includeArchived=true` adds archived ones. Not paged |
| `POST /quality-checkpoints` | `name`, `stage` (`OPERATION_START`, `IN_PROCESS`, `OPERATION_COMPLETION`, `FINAL_RELEASE`), `templateId` (a published IPQC template for the operation stages, FQC for final release, not for one material), optional `productId`, `workCenterId`, `operationSequence` (not for final release), `mandatory` (default true). `201` |
| `PUT /quality-checkpoints/{id}` | The same body; the stage cannot change. Orders already released keep their gates |
| `DELETE /quality-checkpoints/{id}` | Archives it. `204` |

#### Quality on a work order

| Endpoint | Permission | Notes |
|---|---|---|
| `GET /work-orders/{id}/quality` | `WORK_ORDER_VIEW` | `batchId`, `batchNumber`, `batchStatus`, `onHold`; `gates` (name, stage, mandatory, operation, template, `status` of `PENDING`, `IN_INSPECTION`, `ON_HOLD`, `PASSED` or `FAILED`, and every inspection started for it); `ncrs` and `capas` on the batch (empty without `NCR_VIEW`); `reworkOrders`; `rejected` (`rejected`, `toRework`, `scrapped`, `inRework`, `reworkedAccepted`); `unresolved`, one sentence per mandatory gate not passed and rework order still open |
| `POST /work-orders/{id}/quality-gates/{gateId}/inspections` | `INSPECTION_CREATE` | Starts an inspection of the batch with the gate's template, through the inspection engine. Pending or failed gates, while the order is in progress or on hold; a start check before the operation starts, the others while it runs. Returns the gate |
| `POST /work-orders/{id}/quality-hold` | `INSPECTION_APPROVE` | `reason`. Puts the batch on hold and pauses running operations (`QUALITY_ISSUE`). Only while the batch is in progress; an in-process reinspection releases it. Returns the quality view |

#### Rework orders: `/api/v1/manufacturing/rework-orders`

Reading needs `WORK_ORDER_VIEW`; members limited to some facilities see only their facilities' rework orders, anything
else is `404`. Every change returns the detail.

| Endpoint | Permission | Notes |
|---|---|---|
| `GET /rework-orders` | | Newest first. `status` (repeatable), `workOrderId`, `q` (rework or work order number), paged |
| `GET /rework-orders/{id}` | | `rework` (number, work order, batch, product, operation, NCR, `quantity`, `status`, `finalDisposition`), `reason`, `instructions`, the source inspection, approval, times, `reworkedQuantity`, `scrappedQuantity`, `reinspection`, `steps`, `history` |
| `POST /rework-orders` | `REWORK_MANAGE` | `workOrderId`, at least one of `operationId`, `inspectionId` (disposed of as REWORK, on this batch), `ncrId` (on this batch), `quantity` (at most what the operation marked for rework and is not on another rework order), `reason`, optional `instructions`, `steps` (1 to 50 of `description`, optional `workCenterId` in the order's facility). The order must be in progress or on hold; the number is `RW-…`. `201` |
| `POST /rework-orders/{id}/approve` | `REWORK_APPROVE` | Optional `note`. OPEN → APPROVED; under `separateApprover`, not by whoever raised it, except as a recorded self-review |
| `POST /rework-orders/{id}/start` | `PRODUCTION_EXECUTE` | APPROVED → IN_PROGRESS |
| `POST /rework-orders/{id}/steps/{stepId}/complete` | `PRODUCTION_EXECUTE` | Optional `note`. Once per step, while in progress |
| `POST /rework-orders/{id}/complete` | `PRODUCTION_EXECUTE` | `reworkedQuantity`, `scrappedQuantity`, adding up to the quantity; every step done. → AWAITING_INSPECTION |
| `POST /rework-orders/{id}/reinspect` | `INSPECTION_CREATE` | Optional `templateId`. Starts the reinspection on the batch; refused while one is under way |
| `POST /rework-orders/{id}/close` | `REWORK_MANAGE` | → CLOSED with the reinspection's disposition. Refused without a reinspection or before it has a decision other than HOLD |
| `POST /rework-orders/{id}/cancel` | `REWORK_MANAGE` | `reason`. Only from OPEN or APPROVED |

