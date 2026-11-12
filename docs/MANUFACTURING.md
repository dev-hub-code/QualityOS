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
| Planning | Production plans | Phase 2 |
| Work orders | DRAFT → SUBMITTED → APPROVED → RELEASED → IN_PROGRESS → COMPLETED, ON_HOLD, CANCELLED, PARTIALLY_COMPLETED; BOM, recipe and routing snapshot on release | Phase 2 |
| Inventory | Material availability, concurrency-safe reservation, issue, consumption, return; shortages reported, never ignored | Phase 3 |
| Shop floor | Operations PENDING → READY → IN_PROGRESS → PAUSED → COMPLETED, BLOCKED, CANCELLED; quantity reconciliation | Phase 4 |
| Quality | Quality checkpoints on operations reusing inspections, NCR and CAPA; completion blocked by open mandatory gates | Phase 5 |
| Rework | Rework orders and their traceability | Phase 5 |
| Completion | Finished-goods receipt into existing inventory; production batch completed through traceability | Phase 6 |
| Advanced | Downtime, scheduling, costing, OEE, manufacturing analytics | Phase 7 |
| Traceability | Batch genealogy, recalls, serials | Reused |
| Inventory ledger | Lot balances | Reused (no separate movement ledger exists; Phase 3 adds reservations beside the lot balance) |

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

### Permissions

| Permission | Owner, Admin, Plant Manager | Production Manager (new) | Quality Manager | Others with views |
|---|---|---|---|---|
| MANUFACTURING_VIEW | ✓ | ✓ | ✓ | Quality Engineer, Inspector, Auditor, Viewer |
| MANUFACTURING_MANAGE (settings, work centres, machines) | ✓ | ✓ | | |
| BOM_MANAGE, RECIPE_MANAGE, ROUTING_MANAGE | ✓ | ✓ | | |
| BOM_APPROVE, RECIPE_APPROVE, ROUTING_APPROVE | ✓ | ✓ | ✓ | |

All of them belong to the `MANUFACTURING` plan feature, on Professional and Enterprise. As with other features, the
`_VIEW` permission stays after a downgrade so existing records remain readable.

## 4. Phase plan and progress

| Phase | Scope | Status |
|---|---|---|
| 1 Foundation | Settings, product settings, BOMs, recipes, routings, work centres, machines, permissions, navigation | **Built** |
| 2 Planning and work orders | Production plans, work orders and their lifecycle, snapshots on release | Not started |
| 3 Inventory integration | Availability, reservation, issue, consumption, return | Not started |
| 4 Shop floor | Operation execution, quantity reconciliation, machine use | Not started |
| 5 Quality and rework | Quality gates on operations, NCR/CAPA links, rework | Not started |
| 6 Completion | Finished-goods receipt, batch completion, closing work orders | Not started |
| 7 Advanced | Downtime, scheduling, costing, OEE, analytics | Not started |

## 5. Phase 1 limitations

- The product's flat material list stays as it was. A BOM can be started from it, but the two are not kept in step.
- Routing operations record standard times; nothing uses them until scheduling and costing (Phase 7).
- Parameters on recipes and operations are definitions only. Recording actual values is Phase 4.
- Machine status is changed by hand. Work orders do not move it until Phase 4.

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
