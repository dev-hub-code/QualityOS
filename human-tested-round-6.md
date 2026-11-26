# Human testing report, round 6: ProductionOS

**Date:** 26 Nov 2026
**Build:** Docker images built from branch `fix/round-5-bugs`, which holds the round 5 fixes (not yet committed). The round 6 fixes were then added on the same branch and re-tested on rebuilt images.
**How it was tested:** In a real Chromium browser, through the UI, the way people use it. Each user had their own browser profile and login, and each step was done by the role that does it in a real plant. The API was called directly only to set up data quickly, or to read back what was stored.
**What is new in this round:**
- **A food business:** Godavari Foods makes packaged spice mixes. Its plants are Nashik in Maharashtra, Indore in Madhya Pradesh and Hyderabad in Telangana.
- **Process and discrete making in one organisation:** the manufacturing model is set to Hybrid. A bulk blend is made to a recipe in kilograms, and is then packed into counted 100 g pouches from a BOM.
- **A two-level product:** the pouch's BOM uses the blend batch as a sub-assembly. Shortage, release, issue, costing and traceability were all followed across both levels.
- **Food-quality checks:** moisture and curcumin in percent, foreign matter as a yes/no check, net weight in grams, and AQL sampling on packaging.
- **Rework and reinspection:** for the first time, a rework order was taken from shop-floor rejects through to reinspection.
- **A food recall:** started from a supplier's contamination report, and followed into a stock transfer.

---

## 1. Summary

| | |
|---|---|
| Workflows tested | Signup and invitations, facilities, master data and supplier approval, purchasing, receiving, templates and IQC, manufacturing set-up (hybrid), recipes, BOMs and routings, work orders at both levels, shop floor with process measurements, rework and reinspection, final inspection, dispatch, multi-level traceability, recall, stock transfer, calibration, downtime, NCR to CAPA |
| Not tested this round | Audits, customer returns, reports and analytics, billing. Round 5 covered these, and no code in them changed. |
| Users | 23: an owner, then 2 per role (11 roles) |
| Pages opened by URL | 45 pages each as a Viewer, an Operator, a Supplier Manager and an Auditor |
| Accessibility | axe (WCAG 2.1 AA): 10 pages this round touched (work order, recipe, BOM, recall, inspection, downtime, calibration, stock transfers, trace, rework orders). All clean. |
| Issues found | **3 medium, 5 low, 2 cosmetic** (F1–F10). No high issue. |
| Round 5 fixes re-checked | E3, E4, E5, E6, E8 and E14 hold inside the workflows (see section 5). |

**Overall:** Every workflow could be completed by the right roles, and maker/checker held on every approval.

The three medium issues were all about wrong numbers or a silent stall, not a crash:
- **F3:** a BOM could list the same component twice, so the order would need twice the material.
- **F5:** reworked goods waiting for reinspection were in no queue and told no one, so the work order stopped with nobody aware.
- **F8:** downtime entered by hand could overlap on one machine, so the same minutes counted twice in every total.

All 10 issues are fixed and re-tested in the browser.

---

## 2. Issues

### Medium

| ID | Issue | Where | How to reproduce | Evidence |
|---|---|---|---|---|
| **F3** | **A BOM accepted the same component on two lines.** Nothing showed it, and the work order would need both quantities added together. | BOM editor, `BomService` | New BOM for GM-100: POUCH-100 1 PCS, then POUCH-100 1.5 PCS | GM-100 revision 1 had lines 2 and 3 both POUCH-100. It was sent back and line 3 removed before approval. |
| **F5** | **Reworked goods waiting for reinspection reached no one.** The inspection queue lists only goods receipts and returns, and no notification was sent. The work order shows "Completion waits for: Rework order … is open", so it would have stayed stuck until someone happened to open the rework order. | Rework orders, `ReworkService.complete` | Report rejects for rework → raise and approve a rework order → complete it → check the inspectors' notifications and `/inspections` | RW-2026-00001 sat "awaiting inspection" with nothing in either inspector's notifications. |
| **F8** | **Overlapping downtime on one machine was accepted.** FFS-01 had 14:00–15:30 and 15:00–16:00, and the summary showed 2 h 30 min down for 2 h of real time. | Downtime, `DowntimeService.record` and `update` | Record two stops by hand on one machine whose times overlap | Summary: "FFS-01 2 stops, 2 h 30 min" |

### Low

| ID | Issue | Where / evidence |
|---|---|---|
| F1 | A supplier manager whose supplier was rejected was not told. Rejection is the one review outcome the submitter has to act on (inspections and BOMs already report a send-back). | KCS was rejected for "FSSAI licence copy missing". The submitter's notifications showed nothing. |
| F4 | After a material line was fully issued, it still showed **Issue**, with over-issue switched off. The dialog opened with a quantity of 0 and could not be saved. | WO-2026-00003, CORIANDER line after issuing 200 of 200 KG |
| F6 | Every critical parameter on an inspection page had a red "hold" badge, even when every sample passed. It read as the parameter's state. | INS-2026-00008: "Net weight · hold", all 5 samples passing |
| F7 | Reinspecting a rework before finished goods are received needs an in-process (IPQC) template. With none published, the message was "No published template fits this inspection. Publish one first.", which doesn't say which kind of template, or for which product. | RW-2026-00001 for GM-100: the organisation had only IQC and FQC templates |
| F10 | The reinspect dialog offered every published in-process template, including ones for other products. The server refused them with "This template is for a different item." | RW-2026-00002 (GM-BLEND) offered IPQC-GM100 |

### Cosmetic

| ID | Issue | Where / evidence |
|---|---|---|
| F2 | Four messages still showed raw ISO dates: an expired batch at receipt, an expired lot at issue, a transfer received before it was sent, and the overdue-invoice notification. | "TGM-CH-221 (CHILLI) expired on 2026-11-01, before it was received." |
| F9 | `/inspections`, opened by someone who cannot see it, showed "Awaiting inspection: Nothing is waiting." above the permission message. | Production Operator |

### Observations (not bugs)

- **Costing doesn't roll up a sub-assembly's actual cost.** The pouch order's cost leaves out the blend it consumed, and says so: "Sub-assembly GM-BLEND is not costed." Rolling up costs would be a new feature.
- **The dispatch and transfer lists include batches under recall without marking them.** Saving a dispatch is refused: "Batch PB-2026-00002 is under recall RCL-2026-00001. Someone who can manage recalls may still dispatch it, with a reason."
  - A stock transfer of recalled stock is allowed. The recall follows the stock: PB-2026-00002-T1 at Indore was refused for dispatch in the same way.
  - Marking "under recall" in these lists would help.
- **A breakdown entered by hand and left open leaves the machine "available".** Machine status is the live source, and status changes create their own downtime records. Hand entries are a record of what happened.
- **Planned start and end of a new work order have no default.** Both are required, and a planned start in the past is allowed. The draft then warns, for example "GM-BLEND has no approved recipe in effect on 20 Nov 2026."
- **A recall is started from a production batch, not a lot.** A lot's recall report says so, and points to the batches made from it.
- **A planner can raise and submit a work order but not approve or release it.** The production manager does both, and maker/checker held.
- **Approvals of BOMs, recipes, routings and suppliers don't notify the submitter, by design.** Only send-backs and rejections do.

---

## 3. Test accounts and data

Password for every user: `correct-horse-battery`. Domain: `@godavari.test`.

| Role | Users |
|---|---|
| Organisation Owner | Madhav Joshi |
| Organisation Admin | Rukhsana Qureshi, Venkat Rao |
| Plant Manager | Kavya Reddy, Shirish Patwardhan |
| Production Manager | Dattatray Gaikwad, Swapna Naik |
| Production Operator | Balu Shinde, Srinivas Goud |
| Production Planner | Manasi Bapat, Pradeep Chouhan |
| Quality Engineer | Aditi Phadke, Harsha Vardhan |
| Quality Inspector | Jyoti Pawar, Ravi Teja |
| Quality Manager | Ramesh Kamath, Shalini Dubey |
| Supplier Manager | Nilesh Sonawane, Zoya Mirza |
| Viewer | Rekha Agrawal, Uday Bhide |
| Auditor | Joseph Fernandes, Tanvi Sawant |

**Data created:**
- **Plants:** NSK-1 Nashik Spice Works (Maharashtra), IDR-1 Indore Packing Unit (Madhya Pradesh), HYD-1 Hyderabad Masala Plant (Telangana).
- **Suppliers:** TGM (Telangana Grain and Spice Mills), SPP (Sahyadri Poly Packaging), KSS (Kochi Spice Syndicate), KCS (Kutch Common Salt Works). NSP (Nizam Spice Packers) was rejected, for the F1 re-test.
- **Materials:** chilli, coriander, turmeric and salt in KG; laminated pouches and shipper cartons in PCS.
- **Products:** GM-BLEND (bulk blend, KG, process); GM-100 and CM-50 (pouches, PCS, discrete).
- **Definitions:**
  - Recipe GM-BLEND r1: 500 kg batch, with mixing time and temperature limits.
  - BOM GM-100 r1: 100 g blend and 1 pouch.
  - Routings for both products.
  - Work centres WC-BLEND, WC-PACK and WC-HYD; machines RB-01, FFS-01 and FFS-02.
- **Templates:** IQC-CHILLI, IQC-TURM, IQC-CORI, IQC-POUCH (AQL 2.5), IPQC-GM100, IPQC-GMBLEND, FQC-GM100 and FQC-GMBLEND.
- **Purchasing:** PO-2026-00001 to -00005; GRN-2026-00001 to -00005; LOT-2026-00001 to -00007.
- **Inspections:** INS-2026-00001 to -00011. INS-2 failed for foreign matter and was rejected. INS-3 was sent back and corrected.
- **Quality:** NCR-2026-00001 (critical, coriander) to -00005; CAPA-2026-00001 (open).
- **Production:**
  - WO-2026-00001 and -00002: cancelled test drafts.
  - WO-2026-00003: blend, closed short at 495 of 500 kg.
  - WO-2026-00004: pouches, closed short at 3980 of 4000.
  - WO-2026-00005: 50 kg blend, in progress, for the re-tests.
  - Batches PB-2026-00001 to -00003. RW-2026-00001 is closed; RW-2026-00002 is under reinspection.
- **Dispatch:** customers ANR (Karnataka) and DKW (Telangana); DSP-2026-00001 (2000) and DSP-2026-00002 (1500), both shipped.
- **Recall:** RCL-2026-00001 is open, covering PB-2026-00001 and PB-2026-00002.
- **Other:**
  - Stock transfers: STR-2026-00001 (100 recalled pouches, received as PB-2026-00002-T1) and STR-2026-00002 (1000 pouches to Indore).
  - Instruments: MA-01 (overdue) and WB-01.
  - Downtime on FFS-01 and FFS-02. The two overlapping FFS-01 stops were entered before the F8 fix.

---

## 4. Role-based testing

- **Maker/checker held throughout:**
  - Suppliers: the second supplier manager approves.
  - Inspections: QM approves; a send-back needs a note.
  - BOMs, recipes, routings: a self-approval was refused, also through the API.
  - Work orders: submitted by the planner; approved and released by the production manager.
  - Rework orders: raised by the production manager, approved by the QM.
- **Who acts where:**
  - Operators start, measure, report and complete operations, and run rework steps.
  - Inspectors inspect and reinspect.
  - Only roles with a permission see its button. A planner sees no Release; a production manager sees no Approve on a rework order they raised.
- **Pages opened by URL:** 45 pages each as a Viewer, an Operator, a Supplier Manager and an Auditor. Every page a role may not use says so, apart from F9.

---

## 5. Workflows and scenarios

| Workflow | Result |
|---|---|
| W1–W3 Signup, invitations | Pass. A bad GSTIN and a wrong check character are refused. 22 invitations were accepted; an upper-case duplicate was refused. |
| W4 Facilities | Pass. Hyderabad with a Maharashtra GSTIN was refused. Overlapping, zero-length and over-long shifts were refused; a night shift was accepted. A duplicate holiday reads "25 Dec 2026 is already a holiday here." |
| W5 Master data, suppliers | Pass with F1. E3 holds: both supplier managers were notified on submit. A rejection needs a reason. 400 GM of coriander per KG converts; chilli in litres is refused. |
| W6 Purchase orders | Pass. E5 holds: 20000.5 pouches refused. An expected date before the order date is refused. |
| W7 Receiving | Pass with F2. Over-receipt was refused ("Line 1: only 1000.5 remains to be received."). Expiry comes from shelf life. Salt, which needs no inspection, shows "not required". |
| W8–W9 Templates, IQC | Pass with F6. E6 holds: the expected answer is a choice, stored as true/false.<br>• Chilli, foreign matter expected No, answered No: pass.<br>• Coriander, one Yes: critical fail and hold; rejected, raising NCR-1.<br>• Turmeric, curcumin 2.8: fail, sent back, corrected, then passed.<br>• Pouches at AQL 2.5 on a lot of 8000: 200 samples; 3 out of tolerance still passes (acceptance number 10). |
| Manufacturing set-up | Pass with F3.<br>• The Hybrid model is set per product.<br>• Duplicate work centres are refused, ignoring case.<br>• A routing operation at another plant's work centre is refused.<br>• Turmeric in litres on the recipe is refused. |
| Work orders | Pass with F4.<br>• An end before the start is refused; so is 2500.5 pouches (E5).<br>• Release goes ahead with a clear shortage ("CORIANDER is short by 200 KG.").<br>• A rejected lot is not counted as free.<br>• Salt in grams is converted to 100 KG.<br>• Over-issue is refused. |
| Shop floor | Pass.<br>• A temperature outside its limits needs a comment, and the QE is alerted (PROCESS_OUT_OF_LIMITS). Text in a number field is refused.<br>• Quantities must add up; 4001 of 4000 is refused.<br>• Scrap needs a defect. Setup time can't exceed the time run.<br>• Rejects and scrap each raised an NCR. |
| Two levels | Pass.<br>• The blend batch shows as 0 in stock until its FQC accepts it, so the pouch order could not use uninspected blend.<br>• Pouches came only from the inspected lot.<br>• Issue and material variance work across levels (+0.5%). |
| Rework and reinspection | Pass with F5, F7 and F10.<br>• Rework above the marked quantity is refused, and so is a work centre at another plant.<br>• Steps must be done before Complete.<br>• Receiving waits for the rework order to close; the reworked 20 count as good (3960 + 20 = 3980). |
| Final inspection, dispatch | Pass.<br>• Closing waits for the final inspection.<br>• A future dispatch date is refused, as are more than is available (3981) and a fraction.<br>• Two shipments were confirmed. |
| Traceability | Pass. From the pouch batch, the trace goes back through the blend batch to every spice lot, supplier batch, GRN and PO. From the coriander lot, it goes forward through both batches to both customers. The timeline is complete, and a Viewer can trace. |
| Recall | Pass (see observations). Started from the pouch batch; the blend batch was added with a reason; an unknown batch was refused. The dispatch of recalled stock is refused at save, including after a transfer to another plant. |
| Stock transfer | Pass with F2. 1000.5 pouches were refused; the received batch takes a "-T1" number; arriving before being sent is refused. |
| Calibration | Pass. A duplicate ID is refused, ignoring case, as is an interval of 0 days. An overdue instrument (MA-01) is not offered on an inspection. |
| NCR to CAPA | Pass. NCR-1 was assigned to the QE, who raised CAPA-1 from it. (Round 5 took a CAPA through to closure.) |
| Downtime | Pass with F8. Only planned maintenance can be entered ahead of time. |
| Pages by URL | Pass with F9. E4 and E14 hold: the operator's `/purchase-orders/new` is refused, and `/ncrs/new` shows "This record does not exist." |

---

## 6. Fix status

All 10 issues are fixed on branch `fix/round-5-bugs` (with the round 5 fixes), and each was re-tested in the browser on rebuilt images.

Checks:
- **Backend:** `mvn verify` ran 209 unit tests and 788 integration tests, all green. This includes the new `RoundSixFixesIT`, and new cases in `ManufacturingIT`, `AdvancedManufacturingIT` and `ProductionQualityIT`.
- **Frontend:** tsc and eslint are clean, and vitest has 225 tests passing.

| ID | Fix | Re-tested |
|---|---|---|
| F3 | A component can be on only one line of a revision: "This component is already line 1. Change that line's quantity instead." (The product's own material list already refused duplicates.) | A new CM-50 BOM: coriander 20 GM saved, then 5 GM refused with that message. |
| F5 | Completing a rework order notifies everyone who can inspect at that facility: "RW-… is ready for reinspection". It is a new notification type, with its own email preference. | RW-2026-00002: the inspector was notified, and the link opens the rework order. |
| F8 | A stop entered or re-timed by hand can't overlap another stop on the same machine (or on the same work centre, if no machine is given). The message names the existing stop. | "FFS-01 already has a stop from 26 Nov 2026, 2:00 pm, until 26 Nov 2026, 3:30 pm. Change that stop instead." |
| F1 | Rejecting a submitted supplier notifies the submitter, with the reason ("… was rejected"). It is a new notification type, with its own email preference. | Nizam Spice Packers, rejected by supplier manager 2: supplier manager 1 notified. |
| F4 | A fully issued line shows no Issue button unless the organisation allows over-issue. | WO-2026-00005: coriander issued 20 of 20 has no Issue; chilli, turmeric and salt still do. |
| F6 | The badge on a critical parameter reads "critical: a failure holds the batch". | INS-2026-00008 |
| F7 | The message names the template needed: "Publish an in-process (IPQC) template for GM-BLEND first." (or a final or outgoing one, by the batch's stage). | RW-2026-00002 before IPQC-GMBLEND existed. |
| F10 | The reinspect dialog lists only the templates that fit the batch at its stage, from the same source as starting an inspection. It preselects the single fit, and says when none fits. | RW-2026-00002:<br>• Before IPQC-GMBLEND: no options, and the message.<br>• After: only IPQC-GMBLEND, preselected; reinspection started. |
| F2 | The four messages read "1 Nov 2026" style dates. | "TGM-CH-230 (CHILLI) expired on 1 Nov 2026, before it was received." and "Goods cannot arrive before they were sent, on 26 Nov 2026." |
| F9 | The inspection queue section is hidden when the person may not see it; the permission message remains. | Operator: "Inspections · You do not have permission to do this." only. |

Still open from earlier rounds: D18 (checking output against issued material needs a yield rule per product) is waiting on a product decision.
