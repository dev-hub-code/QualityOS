# Backend audit: queries, errors, relationships and repeated requests (2026-11-17)

The remaining part of the deep audit: N+1 queries, query performance, exception handling, entity relationships and
idempotency. Five fixes, one guard test per kind of fix, and no decisions needed from the owner.

## Summary

| # | Area | Finding | Outcome |
|---|------|---------|---------|
| 1 | N+1 | Costing summary ran about 9 queries per closed work order | Fixed: loaded once per list |
| 2 | N+1 | Production analytics ran a receipt query per closed work order | Fixed: one query |
| 3 | N+1 | The scheduling board loaded routings per unreleased order | Fixed: loaded once per board |
| 4 | N+1 | The inspection template list loaded versions and item names per template | Fixed: one query each per page |
| 5 | N+1 | The member list loaded each member's facilities separately | Fixed: one query per page |
| 6 | N+1 | The instruments on an inspection were loaded one at a time | Fixed: one query |
| 7 | Query performance | 52 foreign keys the code searches by had no index | Fixed: V55 |
| 8 | Exceptions | A deadlock or lock wait came back as 500, and a lock wait had no limit | Fixed: 409, 10 s lock timeout |
| 9 | Relationships | Associations and cascades | No change needed |
| 10 | Idempotency | Repeated create and stock requests | No change needed |

## N+1 queries (1–6)

`QueryCountIT` counts the SQL statements each list and summary runs, using `support/SqlCounter`, which wraps the
data source in tests only. It measures with a few records, then with three times as many, and requires the same
count. A count that grows is a query per record.

| Endpoint | Before (few → more) | After |
|---|---|---|
| `/manufacturing/analytics` | 43 → 83 | 33 → 33 |
| `/manufacturing/costing/summary` | 28 → 64 | 19 → 19 |
| `/manufacturing/scheduling` | 19 → 23 | 18 → 18 |
| `/inspection-templates` | grew per template | 9 → 9 |
| `/users` | 14 → 18 | 11 → 11 |
| `/manufacturing/work-orders`, `/production-batches`, `/material-receipts` | already constant | unchanged |

How each was fixed:

- **Costing:** `CostingService` loads operations, centres, requirement lines, movements, lot prices, average prices,
  names, receipts and rework orders once for all the orders in a summary (`Loaded`). One order's cost goes through
  the same code with a list of one.
- **Analytics:** receipts come from `findByWorkOrderIdIn`.
- **Scheduling:** routings and their operations are preloaded, and the routing in effect on each order's planned
  start is chosen in memory.
- **Template list:** versions come from `findByTemplateIdInOrderByVersionNumberDesc`, and material and product
  names are loaded once per page.
- **Member list:** facilities are read with `membership_id = ANY (?)`.
- **Inspection instruments:** read with `findAllById`.

Every other paged list was read by hand. Their row mappers use maps loaded once per page.

## Query performance (7)

The search covered every foreign key with no index leading with it, either directly or after `tenant_id`, which
every query has under RLS. 99 were found. 52 are columns the code filters or joins on, such as
`dispatch_items.dispatch_id`, `downtime_records.work_order_id` and `inspections.facility_id`. V55 indexes those, as
`(tenant_id, column)`. A plan's subscriptions and invoices are counted across organisations, so their index is on
`plan_id` alone.

The other 47 are only checked when a parent row is deleted, and reference rows such as units and categories never
are, so they stay unindexed. `MigrationIT.everyForeignKeyIsIndexedOrReviewed` lists them. A new foreign key must
either get an index or be added to that list after review. The test was checked by removing one index, and it
failed naming that column.

Query plans on the local data are not meaningful at its size: every table there fits in a few pages, so PostgreSQL
scans them whatever the indexes. The indexes are there for when the tables grow.

## Exception handling (8)

`GlobalExceptionHandler` maps domain, validation, access, optimistic-lock and integrity failures to the envelope
with the right status, and hides database detail. One gap: a deadlock, or a row lock not granted in time, fell
through to the catch-all and returned 500 with an error logged. Stock issues, reservations and dispatches lock rows,
so two people working on the same lot could see it.

- `PessimisticLockingFailureException` (deadlock and lock timeout) now returns **409 `CONCURRENT_MODIFICATION`**
  with "Someone else was changing this at the same moment. Try again." It is logged as a warning.
- Application connections now set `lock_timeout` (`DB_LOCK_TIMEOUT`, default 10 s). Before this, a request could
  wait for a lock without limit, holding one of the pool's 10 connections.

Tests: `GlobalExceptionHandlerTest.aLockNotGrantedIsARetryableConflictNotAServerError` and
`FoundationIT.anApplicationConnectionWaitsForARowLockOnlySoLong`.

## Entity relationships (9)

- **JPA associations:** only three parent-and-lines pairs are mapped: purchase order and its lines, receipt and its
  lots, template version and its parameters. Role-to-permissions is also mapped. Each is lazy. The lists that show
  these records don't touch the lines, and the detail views load one record. Everything else refers by id and is
  loaded in bulk, as above.
- **Cascading deletes in the database:** only four foreign keys cascade:
  - membership → facilities
  - membership → roles
  - role → permissions
  - platform user → second factors

  All four are join rows that mean nothing without their parent. Records with history are never deleted; they are
  voided, cancelled or archived.

## Idempotency (10)

- **Stock movements and shop-floor reports** (issue, return, receive, report, measure) already take an
  `Idempotency-Key` that the BFF passes through. A repeat with the same key is not applied twice.
- **Material receipts** are capped at what remains on the purchase order line. The order has a version, so two
  identical receipts racing each other cannot both pass.
- **Dispatches** are capped at the batch's available quantity, under a row lock.
- **State changes** (submit, approve, release, start, complete, close) check the current status, so a repeat is
  refused rather than applied twice.
- **In the frontend**, the record dialogs disable submit while a request is in flight, and the query client retries
  reads only, never changes. Nothing between the browser and the database repeats a change on its own.

A duplicate can still be made by submitting a form again on purpose after it succeeded, which is the person's
choice. Adding keys to every create endpoint would not change that, so no change was made.
