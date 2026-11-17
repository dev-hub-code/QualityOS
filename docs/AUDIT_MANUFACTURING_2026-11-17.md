# Manufacturing business-logic audit — 17 November 2026

Follows the [backend, security and concurrency audit](AUDIT_2026-11-17.md) of the same day. That audit asked whether
the code is safe; this one asks whether its numbers are right. Each calculation was read in the code, worked by hand,
and checked against the standard definition where there is one. Every example below is asserted by a test.

**Summary:** three things were wrong and are fixed, two smaller ones are fixed, and the two that needed a decision were
decided and done.
No finding loses stock or lets bad goods out: the recall gap was still caught at dispatch.

## Findings

| # | Severity | Finding | Status |
|---|---|---|---|
| 1 | Medium | **A fixed line was scaled in the material variance.** A work order line did not keep whether its BOM or recipe line was fixed, so a receipt compared a fixed line with a share of it. A recipe with 1 kg of catalyst whatever the batch, making 8 of 10, expected 0.8 kg; issuing the 1 kg it needed showed +25% and demanded a variance reason. | **Fixed.** V54 adds `work_order_materials.scales` (existing orders filled from their BOM, then frozen with the line); a fixed line is expected in full. `WorkOrderMaterialIT.aFixedLineIsNeededInFullHoweverMuchIsMade`. |
| 2 | Medium | **A sub-assembly under an open recall could go into new production.** The recall hold was checked only at dispatch. The new product would itself have been held there, since the hold follows what a batch was made from, but the recalled parts were already built in. | **Fixed.** Such a batch is no longer offered on a work order nor counted as available, and issuing it is refused, with no override. `WorkOrderMaterialIT.aSubAssemblyUnderAnOpenRecallIsNotOfferedNorIssued`. |
| 3 | Low | **OEE was multiplied from rounded factors.** Availability, performance and quality were each rounded to one decimal before they were multiplied, so OEE could be off by about 0.1 point (68.5% instead of 68.6% below). | **Fixed.** OEE is the product of the unrounded ratios. `OeeFormulaTest`. |
| 4 | Low | **The recipe Scale preview left out scrap.** It showed net quantities, while a work order of the same size adds each line's scrap allowance. | **Fixed.** Each line also gives `withScrap`, shown as "(242.25 KG with scrap allowance)". `ManufacturingIT.aRecipeScalesItsProportionalLinesAndKeepsItsParameters`. |
| 5 | Decision | **Rework from an earlier operation skips the later ones.** Goods rejected at operation 10, reworked and accepted are added to the order's output at receipt, without passing operation 20. That is right when the rework order's own steps cover the rest of the route, which is up to whoever writes it. | **Decided: back on the route.** Accepted rework goes into the next operation's input; only the last operation's own rework is added at receipt. The next operation cannot complete while such rework is open, and rework cannot be raised once the next operation is complete. `ProductionQualityIT.reworkedGoodsGoBackThroughTheRestOfTheRoute`. |
| 6 | Decision | **Setup time counts as running time.** The running clock includes setup, so setup lowers OEE performance rather than availability, where most OEE conventions put it. OEE itself is the same; its split is not. | **Decided: an availability loss.** Availability and performance use operating time (running less setup); utilisation still counts setup. Machines show their setup minutes. `OeeFormulaTest.setupIsLostAvailabilityNotSlowRunning`. |

Also noted, not changed: a machine's units come from the reports on operations it ran last, so an operation moved
between machines part-way credits its units to the last one while its running minutes stay split.

## Worked examples

### BOM and recipe

- **Explosion** (`ManufacturingIT`, e2e `manufacturing`). A gearbox takes 2 shafts; a shaft takes 500 g of EN8 bar.
  Ten gearboxes: 10 × 2 = 20 shafts, then 20 × 0.5 kg = **10 kg** of bar. Each level converts into the component's own
  unit before the next level is worked out, and the walk stops at a product already on the path, so a cycle cannot
  loop.
- **Scrap allowance.** Needed × (1 + scrap %), applied at every level; 2 kg a unit with 10% scrap for 10 units is
  **22 kg**. This is the usual component-scrap convention (net quantity plus a percentage of it).
- **Recipe scaling.** Base 100 kg: resin 95 kg with 2% scrap, catalyst 500 g fixed. A 250 kg batch has factor 2.5:
  resin 95 × 2.5 = **237.5 kg**, with scrap 237.5 × 1.02 = **242.25 kg**; catalyst stays **500 g**.
- **One definition per product.** A product is made from either a BOM or a recipe, never both at once, so the
  explosion and the work order always pick the same one.

### Production quantities

- **Reconciliation** (`ProductionCompletionIT`). Ten kilograms go in; operation 10 processes 10, 9 good and 1 scrap;
  operation 20 may process only the 9 that came through good. The receipt is the last operation's good output: **9**.
  Another quantity is refused. Below the order quantity less the tolerance, a short reason is required.
- **Reports** must add up: processed = good + rejected + scrap, with rework a part of rejected.
- **Rework** (`ReworkService`). An operation's rework order is limited to what it marked for rework and is not on
  another rework order. A rework order closes only when reworked + scrapped equals its quantity. Only rework from an
  operation, accepted on reinspection, adds to output; rework of a batch already counted good does not.

### Material

- **Requirement** = line quantity × order quantity ÷ base quantity × (1 + scrap %), converted to the component's unit.
  Fixed lines are not scaled.
- **Variance at receipt** (after finding 1). Housing: 2 kg of bar a unit and 1 kg of primer fixed; ordered 10, made 8.
  Bar expected 2 × 8 = **16 kg**; primer expected **1 kg**. Issued 16 and 1: both inside tolerance, no reason needed.
- **Issue limits.** Issued + this issue may not pass the requirement unless over-issue is allowed. Reservations on the
  lot are used first. Lots are taken first expiring first; expired lots are never offered; a substitute counts one for
  one. Every movement locks the lot (tests send competing requests at once).

### Costing

- **Planned** = required quantity × average purchase price, plus (planned setup + run minutes) ÷ 60 × each rate.
  Two shafts, 1 kg each at ₹50/kg and 60 minutes each at labour ₹600/h and overhead ₹120/h (`AdvancedManufacturingIT`):
  material 2 × ₹50 = **₹100**, labour 2 h × ₹600 = **₹1,200**, overhead 2 h × ₹120 = **₹240**, total **₹1,540**, unit
  ₹1,540 ÷ 2 = **₹770**. The machine rate is not set, so it is listed as missing and the cost marked incomplete.
- **Actual** = each issued lot at its own price, less returns at the same price, plus running hours × each rate. A
  missing price or rate is listed and the cost marked incomplete, never taken as zero.
- **Scrap cost** = actual total × scrapped ÷ (good + scrapped); **rework cost** = conversion × reworked ÷ (good +
  scrapped). Both are shares of the order's cost, not separately measured effort; rework steps' own time is not costed.
- **Unit cost**: planned over the order quantity, actual over the quantity received.

### OEE (`OeeFormulaTest`)

A 450-minute shift with 30 minutes of planned maintenance: **420** minutes planned production. The machine ran 360
minutes and made 50 units, 48 good, at an ideal 6 minutes each.

| Factor | Formula | Value |
|---|---|---|
| Availability | running ÷ planned production, at most 100% | 360 ÷ 420 = **85.7%** |
| Performance | ideal minutes × units ÷ running | 6 × 50 ÷ 360 = **83.3%** |
| Quality | good ÷ units reported | 48 ÷ 50 = **96.0%** |
| OEE | product of the three | 6/7 × 5/6 × 0.96 = **68.6%** |
| Utilisation | running ÷ shift | 360 ÷ 450 = **80.0%** |

These are the standard definitions (Nakajima). Quality counts first-pass good, so reworked units are a quality loss.
Holidays and a work centre's own shifts come out of shift time. A figure without its data is left out, never shown as
zero. Overall OEE adds the minutes and units of every machine before dividing, so it is weighted by time.

### Quality gates and dispatch

- A batch is **released** only by a final or outgoing inspection that accepts it; reject rejects it, and rework or hold
  puts it on hold.
- **Dispatch** needs a released batch with stock left, is refused while an outgoing inspection of it is open, and is
  held while a recall covers the batch or anything it was made from (override with a reason and `RECALL_MANAGE`).
- **Closing** a work order needs its receipt, no open rework, no unresolved mandatory gate, and, by default, the final
  inspection decided.

## Not covered

- N+1 queries and query performance: the next item on the audit plan.

Process manufacturing, a hybrid organisation and two organisations working side by side are now run end to end in the
browser (`e2e/process-hybrid.spec.ts`). Writing them found one more fault: receiving an order without operations asked
for a variance reason, or left the field out, by comparing material with the ordered quantity rather than the quantity
being entered. The reason is now offered whenever the quantity is entered, and the server decides whether it is needed.
