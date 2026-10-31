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
