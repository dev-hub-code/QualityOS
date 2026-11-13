# Manufacturing Management Engine

ProductionOS is being extended from quality and traceability into manufacturing management for discrete, process
and hybrid production. This document holds the repository analysis, the gap analysis against the
manufacturing specification, the design, and the progress of each phase. It is the source of truth for what is built.

## 1. Repository analysis

The application is a Spring Boot 4 modular monolith (`com.qualityos.<module>`) behind a Next.js 16 BFF, on
PostgreSQL 18 with row-level security. `ModuleBoundariesTest` keeps each module's repositories and entities private,
so modules call each other through services only. Flyway migrations V1–V21 define the schema.

What already exists and is reused rather than rebuilt:

| Need | Existing piece | How manufacturing uses it |
|---|---|---|
| Items made | `product` – `Product` (FINISHED, SEMI_FINISHED), revisions, specifications, a flat material list (`product_materials`) | BOMs, recipes and routings belong to a product. A new BOM can start from the product's material list |
| Items consumed | `material` – `Material`, `UnitOfMeasure` with `to_base`, `MaterialService.convert` | BOM and recipe lines name a material or a product, in any unit that converts to the item's own |
| Where work happens | `organization` – facilities, production lines | A work centre belongs to a facility and can sit on a production line |
| Who may see what | `FacilityScope`, `ReviewPolicy`, `@PreAuthorize(Require.X)`, `PlanAccess` | Work centres and machines follow facility scope. Approvals use the organisation's review rule. A new `MANUFACTURING` plan feature narrows the new permissions |
| Inventory | `materialreceipt` lot stock (`quantity`, `consumedQuantity`), released lots | Phase 3 reserves and issues against these lots through `MaterialReceiptService`. Balances are never changed directly |
| Production record | `traceability` – `ProductionBatch`, consumption with unit conversion, completion, serials, genealogy | Phase 2/6 work orders create and complete production batches through `ProductionService`, so traceability, recall and analytics keep working unchanged |
| Quality | `inspection` (IPQC, FQC templates, `applicableTemplates`), `quality` (NCR, CAPA) | Phase 5 quality checkpoints start inspections on the work order's batch; failures raise NCRs as today |
| Audit, numbering, files | `AuditService`, `DocumentNumberService`, `AttachmentPolicy` | Every state change is audited. Work orders get document numbers |
| Reporting | `reporting` (plain SQL under RLS, `ReportScope`) | Phase 7 adds manufacturing figures the same way |

## 2. Gap analysis

Status: **Built** (in this branch, tested), **Phase n** (planned), **Reused** (already in the product).

| Area | Requirement | Status |
|---|---|---|
| Configuration | Organisation manufacturing model: DISCRETE, PROCESS or HYBRID | Built (Phase 1) |
| Configuration | Approval rule for BOMs, recipes and routings (separate approver or not) | Built (Phase 1) |
| Configuration | Product-level manufacturing model, within what the organisation allows | Built (Phase 1) |
| BOM | Multi-level BOM per product, components are materials or products | Built (Phase 1) |
| BOM | Revisions, DRAFT → SUBMITTED → APPROVED → OBSOLETE, sent back to draft with a note | Built (Phase 1) |
| BOM | Effective dates; a newer approved revision ends the previous one | Built (Phase 1) |
| BOM | Circular reference prevention across BOMs and recipes | Built (Phase 1) |
| BOM | Snapshot frozen on approval; approved lines immutable (service and database trigger) | Built (Phase 1) |
| BOM | Multi-level explosion for a quantity, with unit conversion and scrap | Built (Phase 1) |
| Recipe | Ingredients with base batch size, proportional or fixed quantities | Built (Phase 1) |
| Recipe | Scaling to a batch size | Built (Phase 1) |
| Recipe | Process parameters with target and limits | Built (Phase 1) |
| Routing | Routings with revisions and the same lifecycle | Built (Phase 1) |
| Routing | Operations: sequence, work centre, setup and run time, instructions, parameters | Built (Phase 1) |
| Resources | Work centres on facilities and production lines | Built (Phase 1) |
| Resources | Machines with statuses AVAILABLE, RUNNING, IDLE, MAINTENANCE, BREAKDOWN, INACTIVE, enforced transitions and history | Built (Phase 1) |
| Security | MANUFACTURING_VIEW/MANAGE, BOM_MANAGE/APPROVE, RECIPE_MANAGE/APPROVE, ROUTING_MANAGE/APPROVE in roles and the `MANUFACTURING` plan feature | Built (Phase 1) |
| UI | Manufacturing navigation group shown by permission and model | Built (Phase 1) |
| Configuration | Whether releasing a work order needs a BOM or recipe, and a routing | Built (Phase 2) |
| Planning | Production plans per facility and period, with lines, approval, close and cancel; ordered quantity against each line | Built (Phase 2) |
| Work orders | DRAFT → SUBMITTED → APPROVED → RELEASED → IN_PROGRESS, ON_HOLD, CANCELLED, enforced in the entity and audited with an append-only history | Built (Phase 2) |
| Work orders | BOM or recipe and routing frozen on release; materials and operations worked out from them | Built (Phase 2) |
| Work orders | Starting opens the existing production batch, so traceability and inspections follow it | Built (Phase 2) |
| Work orders | COMPLETED and PARTIALLY_COMPLETED | Phase 6 |
| Inventory | Material availability per work order line, from free stock in the order's facility | Built (Phase 3) |
| Inventory | Concurrency-safe reservation on lots, by hand or oldest-first, and release of reservations | Built (Phase 3) |
| Inventory | Issue to the order's batch (reserved stock first), partial return, consumption counted from the genealogy | Built (Phase 3) |
| Inventory | Shortages shown per line and on start; start blocked or recorded as short, as configured; over-issue refused unless allowed | Built (Phase 3) |
| Inventory | Idempotency keys on reserve, issue and return | Built (Phase 3) |
| Shop floor | Operations PENDING → READY → IN_PROGRESS → PAUSED → COMPLETED, BLOCKED, CANCELLED; quantity reconciliation | Phase 4 |
| Quality | Quality checkpoints on operations reusing inspections, NCR and CAPA; completion blocked by open mandatory gates | Phase 5 |
| Rework | Rework orders and their traceability | Phase 5 |
| Completion | Finished-goods receipt into existing inventory; production batch completed through traceability | Phase 6 |
| Advanced | Downtime, scheduling, costing, OEE, manufacturing analytics | Phase 7 |
| Traceability | Batch genealogy, recalls, serials | Reused |
| Inventory ledger | Lot balances | Reused: lots gain a reserved quantity beside the consumed one; work-order movements are kept in their own append-only ledger |

## 3. Design

### Module

A new `production` module (`com.qualityos.production`) holds all of it, added to `ModuleBoundariesTest.MODULES`.
APIs sit under `/api/v1/manufacturing/...`. It calls `ProductService`, `MaterialService`, `FacilityService`,
`FacilityScope`, `ReviewPolicy` and `AuditService`, and never their repositories.

### Revisioned definitions

BOMs, recipes and routings share one lifecycle, in `RevisionedDefinition`:

```
DRAFT ──submit──> SUBMITTED ──approve──> APPROVED ──obsolete──> OBSOLETE
  ^                  │
  └────send back─────┘
```

- One draft or submitted revision per product and kind at a time. A draft can be discarded; nothing else is deleted.
- Revisions are numbered 1, 2, 3 per product and kind.
- Approval freezes a JSON snapshot of the lines, records who approved, and sets the effective date (today unless
  given). The previous approved revision that would still be in effect ends the day before. An approval dated
  before the current revision began is refused.
- With the organisation's `separateApprover` rule on (the default), the person who submitted cannot approve, except
  under the organisation's existing single-person review rule, which records it as `SELF_REVIEW`.
- A database trigger refuses changes to the lines of anything that is not a draft, and any deletion of a
  non-draft revision.

BOMs and recipes share tables (`boms`, `bom_lines`, with `kind` BOM or RECIPE) because a recipe is a BOM with a base
batch size, fixed or proportional lines, and process parameters. They have separate APIs, screens and permissions.

### Circular references

A product's BOM or recipe may use another product (a sub-assembly or intermediate). Adding such a line, and approving,
is refused when the component already uses the parent anywhere below it, through any revision that is not obsolete.
The check is one recursive query.

### Explosion and scaling

`GET /boms/{id}/explosion?quantity=` walks the tree. Each product component is exploded through its own revision in
effect on the date, or listed as a leaf if it has none. Quantities are converted into each item's own unit, with scrap
added. Recipe scaling multiplies proportional lines by `batchSize / baseQuantity` and leaves fixed lines as they are.

### Machines

Allowed status changes:

| From | To |
|---|---|
| AVAILABLE | RUNNING, IDLE, MAINTENANCE, BREAKDOWN, INACTIVE |
| RUNNING | AVAILABLE, IDLE, MAINTENANCE, BREAKDOWN |
| IDLE | AVAILABLE, RUNNING, MAINTENANCE, BREAKDOWN, INACTIVE |
| MAINTENANCE | AVAILABLE, BREAKDOWN, INACTIVE |
| BREAKDOWN | MAINTENANCE, AVAILABLE, INACTIVE |
| INACTIVE | AVAILABLE |

MAINTENANCE, BREAKDOWN and INACTIVE need a reason. Each change writes an append-only `machine_status_events` row,
which Phase 7 uses for downtime and OEE.

### Facility scope

Work centres and machines belong to a facility, so a member limited to some facilities sees and changes only theirs.
BOMs, recipes and routings are product master data and are visible to every member with `MANUFACTURING_VIEW`, as
products are. Choosing a work centre for a routing operation requires access to that work centre's facility.
Production plans and work orders belong to a facility in the same way.

### Permissions

| Permission | Owner, Admin, Plant Manager | Production Manager (new) | Quality Manager | Others with views |
|---|---|---|---|---|
| MANUFACTURING_VIEW | ✓ | ✓ | ✓ | Quality Engineer, Inspector, Auditor, Viewer |
| MANUFACTURING_MANAGE (settings, work centres, machines) | ✓ | ✓ | | |
| BOM_MANAGE, RECIPE_MANAGE, ROUTING_MANAGE | ✓ | ✓ | | |
| BOM_APPROVE, RECIPE_APPROVE, ROUTING_APPROVE | ✓ | ✓ | ✓ | |
| WORK_ORDER_VIEW (plans and work orders) | ✓ | ✓ | ✓ | Production Planner (new), Quality Engineer, Inspector, Auditor, Viewer |
| WORK_ORDER_MANAGE, PRODUCTION_PLAN_MANAGE | ✓ | ✓ | | Production Planner |
| WORK_ORDER_APPROVE, WORK_ORDER_RELEASE, PRODUCTION_PLAN_APPROVE | ✓ | ✓ | | |

The Production Planner also gets MANUFACTURING_VIEW and the views of facilities, products, purchasing, traceability
and reports.

All of them belong to the `MANUFACTURING` plan feature, on Professional and Enterprise. As with other features, the
`_VIEW` permission stays after a downgrade so existing records remain readable.

### Production plans

A plan belongs to one facility and a period, and lists products with quantities and due dates within it. Lines change
only while the plan is a draft. Work orders are raised from the lines of an approved plan, for the line's product in
the plan's facility. Ordering more than a line planned is allowed and shown, not refused. A plan is closed once none
of its work orders is open, and cancelled only while none of them stands.

### Work order lifecycle

| From | To | Who | Notes |
|---|---|---|---|
| DRAFT | SUBMITTED | WORK_ORDER_MANAGE | The only status in which the order can be edited |
| SUBMITTED | DRAFT | WORK_ORDER_APPROVE | Sent back with a note |
| SUBMITTED | APPROVED | WORK_ORDER_APPROVE | Someone other than the submitter, under the separate-approver rule |
| APPROVED | RELEASED | WORK_ORDER_RELEASE | See release below |
| RELEASED | IN_PROGRESS | WORK_ORDER_RELEASE | Opens the production batch |
| APPROVED, RELEASED, IN_PROGRESS | ON_HOLD | WORK_ORDER_RELEASE | With a reason; resume returns to the status it was held from |
| DRAFT, SUBMITTED | CANCELLED | WORK_ORDER_MANAGE | With a reason |
| APPROVED, RELEASED, ON_HOLD | CANCELLED | WORK_ORDER_APPROVE | With a reason; not when held from IN_PROGRESS |

The allowed moves are in `WorkOrder.TRANSITIONS`. Each move is checked against the current status and the row's
version, so two people releasing or starting the same order cannot both succeed; the loser gets `409` or `422`. Every
move writes a row to `work_order_status_history`, which the database keeps append-only, and an audit entry.

**Release** uses the revisions in effect on the planned start. For a discrete product that is its BOM, for a process
product its recipe, as the product's model says. Whether a BOM or recipe, and a routing, must exist is the
organisation's choice (`workOrderRequiresBom`, default on; `workOrderRequiresRouting`, default off). A routing in
effect is used even when not required, and each of its work centres must be in the order's facility and not archived.
Release stores the approved snapshots on the order and writes:

- one material line per BOM or recipe line, one level deep: the quantity scaled to the order (fixed lines stay as they
  are), plus scrap, converted to the component's own unit. A sub-assembly is listed as needed; it is made on its own
  work order.
- one operation per routing operation, with setup time as it is and run time multiplied by the order quantity, since
  routing run time is per unit. Each starts PENDING; phase 4 moves them.

The detail page shows before release which revisions would be used and what would stop the release.

**Start** creates the production batch through the existing `ProductionService`, for the order's product, facility and
quantity. Consumption, inspections, genealogy and recall then work on it as on any batch.

**Protection in the database.** Work orders are never deleted. Once released, the product, quantity, revisions and
snapshots cannot change, and the batch, once set, stays. Completed and cancelled orders cannot change at all. Released
material lines cannot be rewritten or deleted, and operations keep their order.

### Work order materials

Stock stays with the inventory module. A lot (a received material line) keeps its quantity, what was consumed, and
now what is **reserved** for work orders. Ordinary consumption takes only free stock (remaining less reserved), and the
database refuses consumed plus reserved beyond the quantity. Nothing in the production module writes a balance: it
calls `MaterialReceiptService` to reserve, release and take, and `ProductionService` to consume into or return from a
batch.

- **Availability.** For each material line of a released order: needed, issued, reserved, still to find, and what is
  free in the order's facility (released lots for materials, released batches for sub-assemblies). Anything still to
  find beyond what is free is a **shortage**, listed by line. Lines naming the same component share what is free.
- **Reservation.** By hand (a lot and a quantity) or "reserve what is free", which takes the oldest lots first. Only
  released lots of the line's material in the order's facility, and never more than the line still needs. Allowed while
  the order is released or in progress. Each reservation can be released; cancelling the order releases them all.
  Sub-assemblies are not reserved: they are issued from their batches.
- **Issue** needs the order in progress. From a lot, the order's own reservations on it are used first and any rest
  comes from free stock. Each part becomes a consumption entry on the order's production batch, so genealogy, recall
  and traceability see it at once. More than the line needs is refused unless the organisation allows it.
- **Issued** is counted from the batch's consumption entries that still stand, however they were recorded, so
  consumption entered on the batch page counts too. Entries for components not on the list are shown separately.
- **Return** gives back part or all of one consumption entry to its lot or batch, with a reason. The entry is closed
  and, for a part, a new one keeps what stayed used.
- **Start.** If some line is not issued or reserved in full (or a sub-assembly is not in stock), the order is short.
  With `startRequiresMaterials` start is refused and the shortfalls named; otherwise it starts and the history and
  audit record "Started short" with them. `reserveOnRelease` reserves what is free when the order is released.

**Concurrency.** Every change locks the work order row, so requests for one order are applied one after another, and
each lot row, re-read once locked, so two orders cannot set aside or take the same stock. Automatic reservation takes
what a lot has free at that moment and never fails because another order got there first.

**Idempotency.** Reserve, automatic reserve, issue and return accept an `Idempotency-Key` header. A key already used
on the order returns the current state without moving stock again. The web app sends a fresh key per dialog.

**Record.** Every reservation, release, issue and return is a row in `work_order_material_movements`, which the
database keeps append-only, and an audit entry. Reservations are never deleted, and their open quantity only falls.

## 4. Phase plan and progress

| Phase | Scope | Status |
|---|---|---|
| 1 Foundation | Settings, product settings, BOMs, recipes, routings, work centres, machines, permissions, navigation | **Built** |
| 2 Planning and work orders | Production plans, work orders and their lifecycle, snapshots on release | **Built** |
| 3 Inventory integration | Availability, reservation, issue, consumption, return | **Built** |
| 4 Shop floor | Operation execution, quantity reconciliation, machine use | Not started |
| 5 Quality and rework | Quality gates on operations, NCR/CAPA links, rework | Not started |
| 6 Completion | Finished-goods receipt, batch completion, closing work orders | Not started |
| 7 Advanced | Downtime, scheduling, costing, OEE, analytics | Not started |

## 5. Limitations

Phase 1:

- The product's flat material list stays as it was. A BOM can be started from it, but the two are not kept in step.
- Routing operations record standard times; nothing uses them until scheduling and costing (Phase 7).
- Parameters on recipes and operations are definitions only. Recording actual values is Phase 4.
- Machine status is changed by hand. Work orders do not move it until Phase 4.

Phase 2:

- A plan line's due date is shown, not enforced against the work order's planned dates.
- Status changes are made safe to repeat by the status check and the row version, not by an idempotency key: a
  repeated release or start is refused, never applied twice.
- Nothing reaches COMPLETED or PARTIALLY_COMPLETED until phase 6, so a plan with started orders cannot yet be closed.

Phase 3:

- Sub-assemblies are issued from released batches but not reserved: batches have no reserved quantity yet.
- There is no expiry on lots, so automatic reservation goes by receipt date (first in, first out), not first expiry.
- Requirements are one level deep. A sub-assembly's own materials belong to its own work order.
- Consumption recorded straight on the batch page is not checked against the order's lines or the over-issue rule; it
  still counts as issued, and cannot take reserved stock.
- Material is issued in the line's unit. A lot in another unit is converted, which may leave a remainder below six
  decimal places.

## 6. Phase 1 delivery

**Migration.** `V22__manufacturing_foundation.sql`: ten tenant tables with row-level security, foreign keys within the
tenant, checks and indexes; triggers that freeze approved revisions and their content and keep machine history
append-only; eight permissions; the Production Manager role; the `MANUFACTURING` feature on Professional and
Enterprise, recorded in each plan's history.

**Backend.** `com.qualityos.production`: entities, repositories, `ManufacturingSettingsService`, `WorkCenterService`,
`BomService` (BOMs and recipes), `RoutingService`, `RevisionSupport` (approval, dates, loop check), and six controllers.
Small additions elsewhere: `FacilityService.requireActiveLine` and `codes`, `ProductService.requireActive`,
`PlanAccess.FEATURES`, `Permissions`. APIs are listed in [API_CONTRACTS.md](API_CONTRACTS.md#manufacturing).

**Screens.** A Manufacturing group in the menu (work centres, machines, BOMs or recipes by model, routings,
configuration); list and detail pages for each, with the draft → submit → approve → retire actions, line and operation
editing, parameters, BOM explosion and recipe scaling; a Manufacturing section on each product page.

**Tests.**
- `ManufacturingIT`: 13 tests. They cover the model rules, work centre placement and facility scope, machine
  transitions and append-only history, the revision lifecycle and separate approver, effective dating, loops at any
  depth, explosion arithmetic, recipe scaling and parameters, routings, copying, roles, plan entitlement and
  isolation between organisations.
- `ModuleBoundariesTest` includes `production`.
- Vitest: `manufacturing.test.ts` and navigation tests.
- Playwright: `manufacturing.spec.ts`, which drafts, approves and explodes a two-level BOM and changes a machine's
  status, all from the pages.

## 7. Phase 2 delivery

**Migration.** `V23__production_plans_and_work_orders.sql`: two settings columns; six tenant tables with row-level
security (plans, plan lines, work orders, work-order materials, work-order operations, status history); triggers that
keep work orders and released requirements from being deleted or rewritten and the history append-only; six
permissions; the Production Planner role.

**Backend.** In `com.qualityos.production`: `ProductionPlan`, `ProductionPlanLine`, `WorkOrder` (with its transition
table), `WorkOrderMaterial`, `WorkOrderOperation`, `WorkOrderStatusChange`, their repositories,
`ProductionPlanService`, `WorkOrderService`, and two controllers. `ManufacturingSettingsService` gains the two release
rules and `definitionKind`. Reused: `ProductionService.create` for the batch, `ReviewPolicy` for the separate approver,
`DocumentNumberService` for PP- and WO- numbers, `FacilityScope`, `AuditService`, `MaterialService.convert`.

**Screens.** Production plans and Work orders in the Manufacturing menu: lists, a plan page with lines, ordered
quantities and "Raise work order", and a work order page with each allowed step, what release would use and what stops
it, the frozen materials and operations, the batch link and the status history. The configuration page has the two
release rules.

**Tests.**
- `WorkOrderIT`: 6 tests. They cover the full lifecycle into a production batch, invalid steps, the separate approver,
  release arithmetic and snapshots, double start, hold and resume, cancel rules by permission and stage, the
  configurable release rules, routing work centres in another facility, plans (period, approval, ordering, over-ordering,
  close and cancel), facility scope and isolation between organisations, and the database protections.
- Vitest: `workOrderActions`, `planActions` and the navigation entries.
- Playwright: `work-orders.spec.ts`, from a plan through approval, release, start, hold and resume, from the pages.

## 8. Phase 3 delivery

**Migration.** `V24__work_order_materials.sql`: `reserved_quantity` on lots with a check that consumed plus reserved
never exceeds the quantity; three settings; `material_reservations` and the append-only
`work_order_material_movements`, both with row-level security, triggers that keep them from being deleted or rewritten;
two permissions.

**Backend.**
- Inventory module (`materialreceipt`): lots reserve, release and take reserved stock under a row lock; free lots by
  material and facility; voiding a receipt or correcting a lot below what is reserved is refused.
- Traceability: `ProductionService.consumeReserved`, `consumeEntry`, `returnConsumption` (partial return),
  `activeConsumptions` and `availableBatches`.
- Production: `MaterialReservation`, `MaterialMovement`, `WorkOrderMaterialService`, and the materials endpoints on
  `WorkOrderController`. Release, start and cancel use it.
- The web server's proxy forwards `Idempotency-Key`.

**Screens.** On a released work order: materials with needed, issued, reserved, still to find, free and shortage;
reserve and issue per line; "Reserve what is free"; reservations with release; what went into the batch with return;
and the stock movements. The batch page shows each lot's free stock. Configuration has the three new rules.

**Tests.**
- `WorkOrderMaterialIT`: 4 tests. They cover oldest-first reservation, the lot's reserved balance, reserved stock kept
  from ordinary consumption, issue before start refused, reserved-first issue, over-issue refused, a repeated
  idempotency key, partial return and genealogy, the movement ledger, database protections, shortages, both start
  rules, reserve on release, three orders racing for one lot, cancel releasing reservations, voiding a reserved receipt,
  releasing a reservation, permissions, facility scope and isolation.
- Vitest: `freeQuantity` and the idempotency header in the proxy.
- Playwright: `work-orders.spec.ts` now reserves, starts short, issues and returns from the pages.
