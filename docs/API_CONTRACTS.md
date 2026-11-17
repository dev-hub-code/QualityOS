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
- Archiving a product, material or facility returns `422` while production still uses it, and the message lists each use:
  - a product with an open work order, an approved BOM, recipe or routing in effect, or a BOM line that uses it as a
    component;
  - a material on a line of an approved BOM or recipe in effect, on an open work order, or with open reservations;
  - a facility with a work centre in use, an open work order or an open production plan.
- Products, materials, suppliers, facilities, work centres, inspection templates and audit checklists list archived
  records with `archived=true`, and `POST .../{id}/restore` brings one back (`204`; `422` when it is not archived). Restoring
  needs the permission that archiving needs, and is audited as `<RECORD>_RESTORED`. Codes stay unique across archived
  records, so a restore never clashes.
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
| 4 (built) | `/inspection-templates` (plus `GET /{id}/aql-switching` for where each series of lots stands (per supplier, or production lots: `state`, `recentResults`, `switchingScore`, `mayReduce`, tightened counts) and `POST /{id}/aql-switching` with `supplierId`, `state` and `reason` for a manager's switch (`TEMPLATE_MANAGE`; reduced only with a score of 30, a discontinued series resumes only on tightened, audited as `AQL_SWITCHED`); `inspectionLevel` takes `I`, `II`, `III` or `S1` to `S4`; an AQL inspection's `specification` carries `severity`, `codeLetter`, `aql`, `inspectionLevel` and `rejectionNumber`, and under double sampling (a version's `doubleSampling`) a `secondSample` with `sampleSize`, `acceptanceNumber` and `rejectionNumber` counting both samples; submitting an inconclusive first sample answers `200` with the inspection still `IN_PROGRESS`, `secondSampleDrawn` true and the sample size grown, audited as `INSPECTION_SECOND_SAMPLE`, and starting one in a discontinued series is `422`; `PUT /{id}` for the `name`, `materialId` and `productId`, audited as `TEMPLATE_UPDATED`; inspections already started keep their specification; `/{id}/versions`, `/{id}/publish`), `/inspections` (plus `/{id}/execute`, `/submit`, `/approve`, `/send-back`, `/disposition`, `/cancel`, and `GET /{id}/report.pdf`, the report as a PDF built from the frozen specification, named `<inspection number>.pdf`), `/material-receipts/awaiting-inspection`. A draft's `samplingType` is `FIXED`, `PERCENTAGE`, `FULL` or `AQL`. `AQL` takes `aql`, one of the standard columns from `0.010` to `10`, and `inspectionLevel` (`I`, `II`, `III`), and ignores `acceptanceNumber`. Each inspection then takes its sample size and acceptance number from the ISO 2859-1 single sampling plan for normal inspection for its lot size, rounded up to whole units, and the specification records that acceptance number. Under AQL a sample with any failed parameter is one nonconforming unit, and more than the acceptance number fails the inspection |
| 5 (built) | `/auth/password-reset/{request,confirm}`, `/auth/verify-email`, `/auth/resend-verification`, `/auth/verification-status`, `/auth/invitations/{preview,accept}`, `/invitations` (plus `/{id}/resend`, `/{id}/revoke`), `/notifications` (plus `/unread-count`, `/{id}/read`, `/read-all`, `/preferences`, and `/stream`: server-sent events, one `notifications` event when the stream opens and whenever the signed-in user's inbox changes after a commit, including reads in another tab. The event names nothing; the page reads the inbox as usual. A comment every 25 s keeps it open, it closes after 30 minutes and the browser reconnects, and a user keeps at most 5 open, the oldest closed first. Connections are held in each instance's memory, so with several instances a change reaches only pages connected to the one that made it, and the others catch up at the 5-minute poll). `POST /users` is removed: people join by invitation |
| 6 (built) | `/ncrs` (plus `/{id}/assign`, `/containment`, `/investigation`, `/request-verification`, `/send-back`, `/close`, `/reject`, `/cancel`), `/capas` (plus `/{id}/start-analysis`, `/analysis`, `/finish-analysis`, `/actions`, `/actions/{actionId}/complete`, `/start-implementation`, `/submit-verification`, `/verify`, `/close`, `/cancel`), `/defect-categories`, `/quality/assignees` |
| 7 (built) | `/production-batches` (plus `/{id}/consume`, `/{id}/consumptions/{consumptionId}/reverse`, `/complete`, `/serials`), `/customers` (plus `GET /{id}`, archived ones included), `/dispatches` (plus `GET /{id}`, `?customerId=` on the list, `/{id}/reverse` for a dispatch that never happened, `/{id}/reversals`, `/{id}/reversal-summary`), `/dispatch-reversals`, `/material-corrections` (plus `/{id}/submit`, `/approve`, `/reject`, `/cancel`), `/batches/{id}/correction-history`, `/traceability/search`, `/traceability/batches/{batchNumber}`, `/traceability/products/{productId}`, `/material-receipts/released-lots`. `POST /{id}/consume` takes an optional `uomId`: the quantity is converted to the lot's or batch's own unit, and what was typed is kept on the entry. `POST /material-corrections` takes an optional `uomId` too: the quantity is converted to the lot's unit, or the corrected lot's when one is named, and the change shows both. Moved to a lot in another unit with no quantity, the same amount is converted to it. `POST /inspections` now takes `productionBatchId` or `returnItemId` in place of `receiptItemId`. `templateId` is optional: without it the one fitting template is used. `GET /inspections/applicable-templates?receiptItemId=|productionBatchId=|returnItemId=` lists the fitting templates and the suggested one. A production batch takes IPQC while in progress, FQC once completed, and an optional OQC once released while it has stock left to ship. An OQC covers that remaining quantity. Accepting it keeps the batch `RELEASED`, `HOLD` puts it `ON_HOLD` until the OQC is decided, `REJECT` makes it `REJECTED`, and `REWORK` sends it back for a new FQC. While an OQC is open on a batch, a dispatch from it is `422` ("Batch … is in outgoing inspection …") |
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

## Audit log

| Endpoint | Access | Result |
|---|---|---|
| `GET /audit-logs` | `AUDIT_LOG_VIEW` | The organisation's own entries, newest first, paged. Filters `q` (actor's name or email, target type or ID, correlation ID), `action`, `from` and `to` (days in the organisation's time zone, both included). Each entry has `actor` (null for the system), `action`, `entityType`, `entityId`, `occurredAt`, `reason`, `before`, `after`, `ipAddress`, `correlationId`. Members' account-level events, which have no tenant (`EMAIL_VERIFICATION_SENT`, `EMAIL_VERIFIED`, `PASSWORD_RESET_REQUESTED`, `PASSWORD_RESET`, `PASSWORD_CHANGED`, `LOGIN_FAILED`, `USER_LOCKED`, `PENDING_VERIFICATION_LOGIN`, and the retired `EMAIL_LANGUAGE_CHANGED`), appear from the day each member joined |

## Organisation settings

| Endpoint | Access | Result |
|---|---|---|
| `PUT /organizations/{id}` | `TENANT_MANAGE` | The profile: `name`, `legalName`, `gstin`, `industry`, `countryCode`, `timezone`, `currency`, `locale` (always `en-IN`; the app and its email are English only), `address`, `contactPhone` (optional, digits with an optional `+`, spaces, brackets or dashes, 6 to 20 characters), `version`. The Control Center's organisation page shows the contact number |
| `GET /organizations/current/settings` | Any member | `singlePersonReview`, `selfReviewInEffect`, `activeMembers`, `capaRequiredFrom`, `effectivenessRequiredFrom`, `actionEvidenceRequired`, `version` (null until first saved) |
| `GET /organizations/current/logo` | Any member | The organisation's logo as `image/png` or `image/jpeg`, with an `ETag` (a matching `If-None-Match` is `304`) and `nosniff`. `404` when there is none |
| `POST /organizations/current/logo` | `TENANT_MANAGE` | Multipart `file`, up to 256 KB. Only PNG and JPEG are taken, recognised by their content whatever name or type is claimed, so an SVG is `422`. Replaces any earlier logo. Returns `contentType`, `sizeBytes`, `updatedAt`. Every email for the organisation then shows it, attached inline as `cid:logo`; account emails carry none. Its PDF reports show it at the top, embedded; invoices, which ProductionOS issues, do not. Audited as `ORGANIZATION_LOGO_UPDATED` |
| `DELETE /organizations/current/logo` | `TENANT_MANAGE` | `204`. Audited as `ORGANIZATION_LOGO_REMOVED` |
| `GET /support-access` | `TENANT_MANAGE` | The grant in force (`current`, or null), the last 20 grants, and the last 200 support events (`STARTED` with the operator's reason, `VIEWED` with the path read) |
| `POST /support-access` | `TENANT_MANAGE` | `durationHours` 24, 72 or 168 and an optional `note` (≤ 500). Replaces any grant in force. Audited as `SUPPORT_ACCESS_GRANTED` |
| `POST /support-access/revoke` | `TENANT_MANAGE` | Ends the grant in force, and with it any open support session. `422` when there is none. Audited as `SUPPORT_ACCESS_REVOKED` |
| `GET /platform/organisations/{tenantId}/support-access` | `PLATFORM_ADMIN` | `openUntil`: when the organisation's grant ends, or null |
| `POST /platform/organisations/{tenantId}/support-session` | `PLATFORM_CUSTOMER_MANAGE` and a recent password | `reason` (shown to the organisation). Needs a grant in force (`422` otherwise). Returns a read-only access token for at most 30 minutes or the grant's end; the web tier's `/api/auth/support-session` sets it as the session cookie and never shows it to the page, and the generic proxy refuses this path. Audited in the organisation as `SUPPORT_SESSION_STARTED`. In the session, `/auth/me` has `support: { organizationName, expiresAt }`, and any request other than a read is `403` "Support access is read-only." |
| `GET /ncr-status-labels` | Any member | Every NCR status in workflow order: `status`, `standardLabel` (such as "Under investigation"), `label` (the organisation's own name, or null) and `finished` |
| `PUT /ncr-status-labels` | `TENANT_MANAGE` | `{ "labels": { "CONTAINMENT": "Quarantine" } }` replaces every name at once; a status left out, left blank or given its standard name keeps the standard name. Names are trimmed and at most 40 characters, and two statuses may not share a name, ignoring case (`422`). An unknown status is `400`. Only the names change: an NCR's `status` stays the code, and the workflow is unchanged. The NCR report shows the name. Audited as `NCR_STATUS_LABELS_UPDATED` |
| `PUT /organizations/current/settings` | `TENANT_MANAGE` | Takes every rule (`singlePersonReview`, `capaRequiredFrom`, `effectivenessRequiredFrom`, `actionEvidenceRequired`) and the `version` last read. A missing rule is `400`, except `actionEvidenceRequired`, which stays as it is when left out, a stale version `409`. Audited as `TENANT_SETTINGS_UPDATED` with before and after |

`selfReviewInEffect` is true while `singlePersonReview` is on and the organisation has one active member. Then that
person may approve or send back their own inspection, close an NCR whose verification they requested, and verify or
close a CAPA they own, close a recall or an audit whose closure they requested, and approve their own material
correction, which then needs no second approval. Each of those needs a note (`note` in the body of the recall and audit
`close` calls), and is recorded in the audit log as `SELF_REVIEW` with the step (`INSPECTION_APPROVE`,
`INSPECTION_SEND_BACK`, `NCR_CLOSE`, `CAPA_VERIFY`, `CAPA_CLOSE`, `RECALL_CLOSE`, `AUDIT_CLOSE`,
`MATERIAL_CORRECTION_APPROVE`) and the reason. Once a second
member is active, the usual rule returns without anyone changing the setting.

With `actionEvidenceRequired` on, `POST /capas/{id}/actions/{actionId}/complete` is refused (`422`) until a file is attached to that action (`CAPA_ACTION`). A file on the CAPA as a whole does not count. It is off by default.

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
| Record a return | `POST /dispatches/{id}/reversals` | Needs a confirmed shipment. Stock returns only when an inspection accepts the goods. With `qualityComplaint: true` the return also raises one major NCR with source `CUSTOMER_RETURN` (its `dispatchReversalId` set), in the plant of its first batch. A return's `ncrId` and `ncrNumber` name it |
| Record a complaint | `POST /dispatches/{id}/complaints`, `GET /dispatches/{id}/complaints` | For goods the customer kept, such as a phone call. Takes `reference`, `receivedOn` (between the shipment and today), `severity` and `description`, plus an optional `dispatchItemId`. Needs `TRACEABILITY_VIEW` and `NCR_CREATE`, and a confirmed shipment that was not reversed. Each complaint raises its own NCR with source `CUSTOMER_COMPLAINT` (its `dispatchId` set), in the plant of the batch named or the first. The list returns those NCRs, oldest first |

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
| `POST /recalls`, `PUT /recalls/{id}/customers/{customerId}`, `POST .../contact-attempts`, `POST .../status`, `POST /recalls/{id}/acknowledgements`, `POST /recalls/{id}/request-closure`, `POST /recalls/{id}/batches`, `PUT /recalls/{id}/capa` | `RECALL_MANAGE` |
| `POST /recalls/{id}/customers/{customerId}/exception`, `POST /recalls/{id}/send-back`, `POST /recalls/{id}/close` | `RECALL_CLOSE` |

A sent email is an attempt with the outcome `NO_REPLY`. Only `REACHED` moves a customer to `CONTACTED`, and only an
acknowledgement allows `RESOLVED`. Closing needs every customer resolved or covered by an exception, any linked CAPA closed,
and a second person.

`GET /recalls` takes `q`, matched in any case against the recall number, the reason or a batch number. `GET /dispatches` takes `q` too, matched against the dispatch number, the reference, or the customer's name or code. A member limited to some facilities still sees only what lies wholly inside them.

While a recall is open, `POST /recalls/{id}/batches` adds batches found later (`batchNumbers`, `reason`). Their
customers get contact records as at the start, a batch already in the recall is skipped, and adding none is a 422. It is
audited as `RECALL_BATCHES_ADDED` with the numbers and reason. `PUT /recalls/{id}/capa` links, changes or (with an empty
`capaId`) unlinks the CAPA, audited as `RECALL_CAPA_LINKED`.

| Calibration | Permission |
|---|---|
| `GET /instruments`, `GET /instruments/{id}`, `/calibrations`, `/usage`, `/impact-reviews`, `/qr.svg` | `CALIBRATION_VIEW` |
| `POST /instruments`, `PUT /instruments/{id}`, `POST /instruments/{id}/status`, `POST /instruments/{id}/calibrations`, `POST /instruments/{id}/impact-reviews/{reviewId}/decision` | `CALIBRATION_MANAGE` |
| `GET /instruments/usable`, `POST /inspections/{id}/instruments` | `INSPECTION_EXECUTE` |

A failed calibration lists each inspection that used the instrument since its last good calibration, and raises one MAJOR
NCR (source `CALIBRATION`) for them. Shop-floor operations whose measurements named the instrument in that time are
named in the same NCR. With nothing in doubt, no NCR is raised. `GET /instruments/{id}/impact-reviews` groups
the reviews by failed calibration, newest first, with the NCR, the number still pending, and `otherUses` (`label`,
`link`, `usedOn`) for the operations, which need no decision of their own. `GET /instruments/{id}/usage` lists
inspections and operations alike, each with `label` and `link`; `inspectionId` is null for an operation. A decision takes `decision`
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
person.

`POST /audits` and `PUT /audits/{id}` take an optional `repeatEveryMonths` (1 to 36). A `PUT` without it makes the audit a
one-off. Closing a repeating audit plans the next one, with the same checklist, scope and lead auditor, that many months
after its planned date (moved on by whole cycles if that date has passed). The detail returns `repeatEveryMonths`,
`nextAuditId` and `nextAuditNumber`. The repeat is skipped, and `AUDIT_REPEAT_SKIPPED` is logged with the reason, when the
checklist or facility is archived or the lead auditor has left. The QR code holds only a link, which asks whoever scans it to sign in.

## Analytics and reports

All need `REPORT_VIEW`. `from` and `to` are dates in the organisation's time zone. They default to the last 90 days and
may span two years at most. `facilityId` narrows the result; a facility the caller may not see is a 404.

| Method | Path | Result |
|---|---|---|
| GET | `/api/v1/analytics/overview` | Key figures, chart series, pending approvals, recent activity, and `production`: open and late work orders, work orders completed and on time, shop-floor loss and unplanned downtime |
| GET | `/api/v1/reports/{slug}` | Report as JSON: columns, up to 5,000 rows, `truncated`. The PDF stops there too; the `.xlsx` holds up to 100,000 rows and says where it stops. Both limits are set under `qualityos.reports` |
| GET | `/api/v1/reports/{slug}.pdf` | The same report as a PDF, audited as `REPORT_EXPORTED` |
| GET | `/api/v1/reports/{slug}.xlsx` | The same report as a workbook, audited as `REPORT_EXPORTED` |

Slugs: `inspection`, `supplier-quality`, `material-rejection`, `ncr`, `capa`, `calibration`, `audit`,
`batch-traceability`, `work-orders` and `production-quality` (`WORK_ORDER_VIEW`), `downtime`
(`MANUFACTURING_VIEW`), `oee` (`PRODUCTION_ANALYTICS_VIEW`, at most 366 days) and `production-cost`
(`PRODUCTION_COST_VIEW`, at most 366 days). Each also needs the view permission for what it lists, or the call is a 403.
`oee` and `production-cost` are worked out by the production module's own analytics and costing, through
`ComputedReport`, so the report and the screen agree.

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

Adding a user or facility, starting an inspection or storing an attachment that takes the organisation past 80% of a plan
limit, or onto it, sends a `PLAN_LIMIT_WARNING` notification to holders of `SUBSCRIPTION_MANAGE`, the person who acted
included, with a link to billing. Each threshold warns once as it is crossed.

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
| `GET`, `POST /api/v1/support/tickets` | Any member | The caller's own requests, or all of the organisation's with `TENANT_MANAGE`. `POST` takes `subject`, `message` and returns `id` |
| `GET /api/v1/support/tickets/{id}`, `GET .../{id}/messages` | Any member who can see it | The request, and its messages and support's replies. Internal notes never appear. Unknown or someone else's is `404` |
| `POST /api/v1/support/tickets/{id}/replies` | Any member who can see it | `body`. Reopens a ticket that was waiting for the customer or resolved. A closed one is `422` |
| `GET /api/v1/platform/settings` | | Read settings |
| `PUT /api/v1/platform/settings` | `PLATFORM_SETTINGS_MANAGE`, password | Save, with `version` and `reason` |
| `GET /api/v1/platform/announcements` | | List |
| `POST /api/v1/platform/announcements`, `PUT .../{id}` | `PLATFORM_CUSTOMER_MANAGE` | Create or save, with `version` and `reason`. Optional `email: true` also emails every active member it reaches, once per organisation, on or after `startsOn` |
| `GET /api/v1/platform/support/tickets` | | Filter by `tenantId`, `status` |
| `POST /api/v1/platform/support/tickets` | `PLATFORM_CUSTOMER_MANAGE` | Open with `tenantId`, `subject`, `note` |
| `PUT /api/v1/platform/support/tickets/{id}/status` | `PLATFORM_CUSTOMER_MANAGE` | Change status, with `version` |
| `GET`, `POST /api/v1/platform/support/tickets/{id}/notes` | `POST` needs `PLATFORM_CUSTOMER_MANAGE` | Read or add notes. `toCustomer: true` sends a reply the customer sees, only on a ticket they raised (`422` otherwise). Notes show `shared` |
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

Since phase 7 a work centre also takes `capacityUnits` (1 to 1000, default 1; used only when it has no active machines) and optional `labourRate`, `machineRate` and `overheadRate` per hour, in the organisation's currency; a `PUT` without a rate clears it. Facility shifts (`POST /api/v1/facilities/{id}/shifts`) take optional `breakMinutes` (default 0) and `daysOfWeek` (Monday 1, Tuesday 2 … Sunday 64, added together; default 127, every day).
| `GET /machines`, `POST /machines` | `POST`: `MANUFACTURING_MANAGE` | Filter by `q`, `workCenterId`, `status` |
| `GET /machines/{id}`, `PUT ...` | `PUT`: `MANUFACTURING_MANAGE` | Detail with the last 100 status changes |
| `GET /machines/{id}/instruments`, `POST ...` (`instrumentId`), `DELETE .../{instrumentId}` | `MANUFACTURING_MANAGE` to change | Instruments kept at the machine. Only instruments of the machine's facility that are not retired. Each change returns the list |
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
An order in progress closes as COMPLETED or PARTIALLY_COMPLETED through finished-goods receipt and close, below.

Reserve, automatic reserve, issue and return take an optional `Idempotency-Key` header (1 to 100 characters). A key
already used on the same work order returns the current materials without moving stock again. With
`startRequiresMaterials`, `start` is refused with the shortfalls; otherwise a short start is recorded in the history.
Lots from `GET /material-receipts/released-lots` and elsewhere now carry `reservedQuantity` and `facilityId`; ordinary
consumption takes only `remainingQuantity` less `reservedQuantity`.

Lots can expire. A material takes an optional `shelfLifeDays` (1 to 36500). A receipt item takes an optional
`expiryDate`; left out, it is `receivedOn` plus the material's shelf life, and one before `receivedOn` is `422`. Receipt
items, lots and a work order line's `sources` carry `expiryDate`. Released lots and sources leave out expired lots and
come first expiring first (then lots without expiry, oldest first), which automatic reservation follows. Reserving,
issuing (reserved or not) and consuming an expired lot is `422`. A lot is usable through its expiry date, in the
organisation's time zone.

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
| `POST /operations/{id}/reports` | `PRODUCTION_EXECUTE` | `quantity`, `goodQuantity`, `rejectedQuantity`, `scrapQuantity` (must add up to `quantity`), optional `reworkQuantity` (within rejected), `comment` and `defectCategoryId`. Accepts `Idempotency-Key`. With `rejectionRaisesNcr` on, a report with anything rejected or scrapped raises one NCR (source `PRODUCTION`, severity MINOR) on the order's batch |
| `GET /operations/defect-categories` | `WORK_ORDER_VIEW` | The organisation's defect categories, for the report form, without `NCR_VIEW` |
| `POST /operations/{id}/measurements` | `PRODUCTION_EXECUTE` | `parameterCode`, `value`, optional `comment` and `instrumentId`. The instrument must be at the order's facility, active and within its calibration. Accepts `Idempotency-Key`. The operation's detail offers `instruments`, those kept at its work centre's machines first |
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

#### Completing a work order

| Endpoint | Permission | Notes |
|---|---|---|
| `GET /work-orders/{id}/completion` | `WORK_ORDER_VIEW` | `orderedQuantity`, `expectedQuantity` (the operations' good output plus accepted rework; null without operations), `rejectedQuantity`, `scrapQuantity`, `reworkedQuantity`, `serialsRequired`, `materials` (per line: `expected`, `issued`, `difference`, `percent`, `outside`), `receiveBlockers`, `receipt`, `closeBlockers`, `closesAs`, `completedAt`, `completedByName` |
| `POST /work-orders/{id}/receipt` | `WORK_ORDER_COMPLETE` | Optional `quantity` (required without operations; otherwise must equal the expected quantity), `shortReason` (required below the order quantity less `completionTolerancePercent`), `varianceReason` (required when a material line is off by more than `materialVariancePercent`), `serialNumbers` (up to 1000; one per unit when `serialTracking` is on and the product is discrete). Completes the batch as finished goods and releases open reservations. Optional `Idempotency-Key`: the same key returns the current view; any other second receipt is `422` |
| `POST /work-orders/{id}/complete` | `WORK_ORDER_COMPLETE` | Optional `reason`, required when the order closes short. Needs the receipt, mandatory gates and rework settled, the batch not on hold and, with `completionRequiresFinalInspection`, its final inspection decided. → COMPLETED or PARTIALLY_COMPLETED |
| `GET /work-orders/production-record?batchId=` | `WORK_ORDER_VIEW` | How the batch was made: work order, BOM or recipe revision, routing revision, operations with operators and machines, gates, rework orders, receipt. `data` is null for a batch no work order made, or one in another organisation |
| `GET /work-orders/{id}/production-record.pdf` | `WORK_ORDER_VIEW` | The same record as a PDF, named `<work order number>-production-record.pdf`. `422` when the order has not made a batch yet |

Completing a batch made by a work order through `POST /api/v1/production-batches/{id}/complete` is `422`. The
settings gain `completionTolerancePercent`, `materialVariancePercent` (0 to 100), `completionRequiresFinalInspection`
and `serialTracking`. V31 adds `rejectionRaisesNcr` (default on). An NCR raised by a report carries `workOrderId`.

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

#### Downtime: `/api/v1/manufacturing/downtime`

Reading needs `MANUFACTURING_VIEW`; limited members see their facilities only, anything else is `404`. Times are
instants; `minutes` runs to now while a stop is open.

| Endpoint | Permission | Notes |
|---|---|---|
| `GET /downtime` | | Newest first, paged. Filters `facilityId`, `workCenterId`, `machineId`, `workOrderId`, `category`, `open`, `from`, `to` (instants; stops overlapping them) |
| `GET /downtime/{id}` | | One stop: where, `source` (`SHOP_FLOOR`, `MACHINE`, `MANUAL`), `category`, `planned`, times, `minutes`, reason, department, comments, who recorded and ended it |
| `GET /downtime/departments` | | The departments a stop can be blamed on, across the facilities the caller can see, each with `facilityId` and `facilityCode` |
| `GET /downtime/summary` | | `from`, `to` (dates, at most 366 days), optional `facilityId`, `groupBy` (`MACHINE` default, `WORK_CENTER`, `PRODUCTION_LINE`, `FACILITY`, `CATEGORY`, `DEPARTMENT`). Rows of `stops`, `minutes`, `plannedMinutes`, `unplannedMinutes`, and totals |
| `POST /downtime` | `DOWNTIME_MANAGE` | `machineId` or `workCenterId`, optional `workOrderId`, `category` (not `ORDER_ON_HOLD`), `startedAt`, optional `endedAt`, `reason`, optional `responsibleDepartmentId` (one of the facility's active departments), `comments`. Only `PLANNED_MAINTENANCE` may start in the future, and then needs `endedAt`. `201` |
| `PUT /downtime/{id}` | `DOWNTIME_MANAGE` | Any of `category`, `startedAt`, `endedAt`, `reason`, `responsibleDepartmentId`, `comments`. On a stop not entered by hand only the last three may change |
| `POST /downtime/{id}/end` | `DOWNTIME_MANAGE` | Optional `endedAt` (default now). Manual stops only; the others end when the operation resumes or the machine is set back |

Stops from the floor and from machine status are recorded by the existing endpoints. Evidence uses
`/api/v1/attachments` with `entityType=DOWNTIME`.

#### Scheduling

| Endpoint | Permission | Notes |
|---|---|---|
| `GET /scheduling?facilityId&from&to` | `WORK_ORDER_VIEW` | Up to 62 days. `shiftsDefined`; `workCenters` with `units` and per day `capacityMinutes`, `plannedDowntimeMinutes`, `loadMinutes`, `overloaded`; `orders` by priority with `remainingMinutes` and `warnings`; `conflicts` (centre, day, capacity, load, order numbers) |
| `POST /work-orders/{id}/reschedule` | `WORK_ORDER_MANAGE` | `plannedStart`, `plannedEnd`, optional `priority`, `reason`. Until the order is completed or cancelled. Returns `order`: its place on the board with warnings. Audited as `WORK_ORDER_RESCHEDULED` |

#### Costing: `/api/v1/manufacturing/costing`

Needs `PRODUCTION_COST_VIEW`. Money is text, two decimal places, in the organisation's `currency`; null when unknown.

| Endpoint | Notes |
|---|---|
| `GET /costing/work-orders/{id}` | Released orders only (`422` otherwise). `materials` and `operations` with planned and actual cost; `planned` and `actual` totals (`material`, `labour`, `machine`, `overhead`, `scrap`, `rework`, `total`, `unit`); `complete`, and `missing` listing each missing price or rate |
| `GET /costing/summary?from&to&facilityId` | Orders closed in the period with planned and actual totals and unit costs; overall totals; `complete` |

#### Production analytics

| Endpoint | Permission | Notes |
|---|---|---|
| `GET /analytics?from&to&facilityId` | `PRODUCTION_ANALYTICS_VIEW` | Up to 366 days. `production`, `plannedVersusActual`, `ordersCompleted`, `ordersPartiallyCompleted`, `ordersOnTime`, `workOrderStatus`, `quality` (quantities and rejection, scrap and rework rates), `efficiency`, `materialVariance`, `machines` and `overall` (shift, planned production, running and unplanned downtime minutes; utilisation, availability, performance, quality, OEE; `notes`), `downtimeByCategory`, and `cost` only with `PRODUCTION_COST_VIEW`. Percentages are 0 to 100; a figure without the data it needs is left out, never zero |
