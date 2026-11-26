# Human testing report, round 7: QualityOS

**Date:** 26–27 Nov 2026
**Build:** Docker images built from `dev` at 6cfac6a, which includes the round 5 and round 6 fixes. The round 7 fixes are on branch `fix/round-7-bugs` (not yet committed). After each fix, the images were rebuilt and testing restarted from signup.
**How it was tested:** In a real Chromium browser, through the UI, the way people use it. Each user had their own browser profile and login, and each step was done by the role that does it in a real plant. The API was called directly only to read back what was stored.

**What is new in this round:**
- **A wire and cable maker:** Konkan Cables. Its plants are Ratnagiri in Maharashtra, Vapi in Gujarat and Bhiwadi in Rajasthan.
- **Mixed units through one chain:** copper rod in KG becomes bulk wire in metres (M), which is then wound into counted 90 m coils (PCS). A recall followed the chain across all three units.
- **Rework raised before finished goods are received.** In round 6, the finished goods were received after the rework was raised.
- **Electrical and dimensional checks:**
  - Conductor resistance per km.
  - Insulation thickness.
  - Spark test (yes/no).
  - Coil length.
  - Limiting oxygen index.
  - A Kelvin-bridge instrument recorded against an inspection.
- **Complete lifecycles:**
  - NCR to CAPA: verified effective, then both closed.
  - Internal audit: from checklist to approved closure.
  - Recall: from start to approved closure, with customers contacted and resolved.

---

## 1. Summary

| | |
|---|---|
| Workflows tested | Signup and invitations, facilities, master data and supplier approval, purchasing, receiving, templates and IQC, manufacturing set-up (discrete), BOMs and routings, work orders at two levels, shop floor, rework and reinspection, final inspection, dispatch, traceability, stock transfer, recall to closure, calibration, downtime, NCR to CAPA to closure, internal audit to closure, notifications |
| Not tested this round | Customer returns, reports and analytics screens, billing, process (recipe) manufacturing. Round 6 covered recipes, and round 5 covered the rest; no code in them changed. |
| Users | 23: an owner, then 2 per role (11 roles) |
| Pages opened by URL | 45 pages each as a Viewer, an Auditor, an Operator, an Inspector and a Supplier Manager |
| Accessibility | axe (WCAG 2.1 AA) on 8 pages: dashboard, recall, audits, rework orders, stock transfers, calibration, downtime and CAPAs. All clean. |
| Issues found | **1 high, 1 medium** (G1, G2). Both are fixed and re-tested from the beginning. |
| Restarts | 2. After each fix, the images were rebuilt and the whole run repeated from signup. |

---

## 2. Issues

### High

| ID | Issue | Where | How to reproduce | Evidence |
|---|---|---|---|---|
| **G1** | **Finished goods could be received while goods marked for rework were not yet on a rework order, leaving the reworked goods with no way back.** <br>• Once the finished goods were received and released, the rework order could still be raised.<br>• Its reinspection then asked for an outgoing (OQC) template, because the batch was now released.<br>• So the reworked coils could never rejoin the batch, and the order stayed short. | Work order receipt and rework orders, `CompletionService.receiveBlockers` and `ReworkService.create` | Coil order of 20: on operation 10 report 18 good and 2 for rework → receive finished goods (18) → raise a rework order for the 2 | WO-2026-00005 (first run): 18 of 20 received, then RW raised. Its reinspection was refused for want of an OQC template. |

### Medium

| ID | Issue | Where | How to reproduce | Evidence |
|---|---|---|---|---|
| **G2** | **Recall totals added different units together.** A recall on the bulk wire (M) also covers the coils made from it (PCS). "Still with customers" showed **105.5**, which is 100.5 m of wire plus 5 coils. The recovered percentage was computed on that sum. | Recall page, dashboard, customer list, report and PDF, `TraceabilityService.recallLine` and `RecallService` | Dispatch 100.5 m of wire and 5 coils made from it → start a recall on the wire batch → open the recall | RCL-2026-00001: "Dispatched 105.5 · Still with customers 105.5" |

### Observations (not bugs)

- **Receiving finished goods asks "Why material use was off" when some of the output went to rework.** 18 of 20 coils used all the wire issued for 20. The check against issued material needs a yield rule per product. This is D18, still waiting on a product decision.
- **The Plant Manager role can view traceability, but not send or receive stock transfers or dispatch.** Production managers do these. This is a role-design question, not changed.
- **The dispatch and transfer lists still offer batches under recall without marking them** (known from round 6).
  - Saving a dispatch is refused, unless a recall manager gives a reason. That override then adds the customer to the recall.
  - A transfer of recalled stock is allowed, and the recall follows it: PB-2026-00002-T1 at Bhiwadi was also refused for dispatch.
- **A breakdown entered by hand and left open leaves the machine "available"** (known: machine status is the live source).
- **A CAPA action can be assigned to any member, even a Viewer, who is then notified** (CAPA_ACTION_ASSIGNED). This is by design: actions often sit with people outside quality.
- **The quality engineer cannot plan, run or close audits.** Audits need the audit-manage permission (QM, Auditor).

---

## 3. Test accounts and data

| Role | Users |
|---|---|
| Organisation Owner | Anand Sawant |
| Organisation Admin | Sneha Desai, Farhan Kazi |
| Plant Manager | Vinayak Gokhale, Hetal Parikh |
| Production Manager | Santosh Mhatre, Bhavna Rathore |
| Production Operator | Ganesh Kadam, Imran Pathan |
| Production Planner | Pooja Salvi, Rakesh Meena |
| Quality Engineer | Nikhil Bapat, Ritu Sharma |
| Quality Inspector | Sameer Naik, Dhruv Patel |
| Quality Manager | Leena Kulkarni, Arvind Shekhawat |
| Supplier Manager | Mohan Rane, Kirti Joshi |
| Viewer | Aarti Gawde, Yusuf Shaikh |
| Auditor | Prakash Iyer, Meera Chauhan |

All passwords are the test password used in earlier rounds. Email addresses are on `konkan.test`.

**Data created (final run):**
- **Plants:** RTN-1 Ratnagiri Wire Mill (Maharashtra), VAP-1 Vapi Insulation Works (Gujarat), BHW-1 Bhiwadi Coiling Unit (Rajasthan).
- **Suppliers:** HCR (Hindustan Copper Rods), SPL (Sarvodaya Polymers), MWD (Mewar Wooden Drums) and KPC (Kalinga Packaging Co).
- **Materials:**
  - Copper rod CU-ROD and PVC compound PVC-FR, in KG.
  - Wooden drums DRUM and coil labels LABEL, in PCS.
  - Shrink film SHRINK, in KG.
- **Products:** WIRE-15 (bulk wire, M), COIL-90 and COIL-180 (coils, PCS).
- **Definitions:**
  - BOMs: WIRE-15 r1 (copper and PVC per metre); COIL-90 r1 (90 m of WIRE-15, a label and shrink film).
  - Routings for both products.
  - Work centres WC-DRAW, WC-EXT and WC-COIL at Ratnagiri, and WC-VAP at Vapi.
  - Machines DR-01, EX-01, EX-02 and CL-01.
- **Templates:**
  - Incoming: IQC-CU, IQC-PVC, IQC-DRUM.
  - In-process: IPQC-WIRE, IPQC-COIL90.
  - Final: FQC-WIRE, FQC-COIL90.
- **Purchasing:** PO-2026-00001 to -00005; GRN-2026-00001 to -00005; LOT-2026-00001 to -00006 and more.
- **Inspections:** INS-2026-00001 to -00008 and more.
  - Copper lot HCR-H102 failed OXIDE and was rejected.
  - The wire IPQC (INS-5) failed THICK and was conditionally accepted with a justification.
- **Quality:**
  - NCR-2026-00001 (copper, closed through CAPA-2026-00001, which was verified effective).
  - NCR-2026-00002 to -00005.
  - NCR-2026-00006, from the audit finding, closed.
- **Production:**
  - WO-2026-00001: 3000 m of wire, closed short at 2950 m ("50 m insulation too thin").
  - WO-2026-00002: 20 coils, complete, including 2 reworked under RW-2026-00001 (closed on its reinspection).
  - Batches PB-2026-00001 (wire), PB-2026-00002 (coils) and PB-2026-00002-T1 (5 coils at Bhiwadi).
- **Dispatch:**
  - Customers CBL (Coastal Builders, Karnataka) and SEL (Sahyadri Electricals, Maharashtra).
  - DSP-2026-00001: 100.5 m of wire to CBL.
  - DSP-2026-00002: 10 coils to SEL.
  - DSP-2026-00003: 2 coils to CBL, dispatched during the recall by a QM override with a reason.
- **Recall:** RCL-2026-00001, closed. It started from the wire batch and covers the coils and the transferred coils. Both customers acknowledged it and were resolved, and a second QM approved the closure.
- **Audit:** checklist PROC-EXT; AUD-2026-00001 (process, Ratnagiri), closed.
- **Other:**
  - Stock transfer STR-2026-00001: 5 coils, Ratnagiri to Bhiwadi.
  - Instruments: MIC-01 (overdue) and KLV-01.
  - Downtime on EX-01 and DR-01.

---

## 4. Role-based testing

- **Maker/checker held throughout:**
  - Suppliers: the second supplier manager approves; a rejection needs a reason and notifies the submitter.
  - Templates: written by the QE, published by the QM.
  - BOMs and routings: the second production manager approves.
  - Work orders: the planner raises and submits; the production manager approves and releases. Approve and Release are not offered to the planner.
  - Rework orders: raised by the production manager, approved by the QM. Approve is not offered to the person who raised it.
  - CAPA effectiveness and NCR closure are by the QM. The QE, who did the work, has no Close button.
  - Audit closure: "Someone other than you must approve closing this audit." The second QM approved.
  - Recall closure: the QM who asked for it has no Approve. The second QM approved.
- **Who acts where:**
  - Operators start, report and complete operations, and run rework steps.
  - Inspectors inspect and reinspect, and are notified when rework awaits them.
  - Recalled stock can be dispatched only by someone who manages recalls, with a reason.
- **Pages opened by URL:** 45 pages for each of five roles. Every page a role may not use shows "You do not have permission to do this." `/ncrs/new` shows "This record does not exist." No hidden page offered an action.
- **Notifications seen:** REWORK_AWAITING_INSPECTION (inspector), CAPA_ACTION_ASSIGNED (action owner), CAPA_AWAITING_VERIFICATION and NCR_AWAITING_CLOSURE (QM), MATERIAL_AWAITING_INSPECTION, and SUPPLIER_REJECTED (submitter).

---

## 5. Workflows and scenarios

| Workflow | Result |
|---|---|
| Signup, invitations | Pass. 22 invitations accepted; an upper-case duplicate refused. |
| Facilities | Pass.<br>• Bhiwadi with a Maharashtra GSTIN was refused.<br>• A duplicate department ("draw" next to "DRAW") was refused.<br>• Overlapping and zero-length shifts, a too-long break and a duplicate holiday were refused. |
| Master data, suppliers | Pass.<br>• Wrong state codes and wrong GSTIN check characters were refused.<br>• A rejection needs a reason, and the submitter is notified.<br>• A spec with minimum above maximum is refused.<br>• PVC compound in metres is refused. |
| Purchase orders, receiving | Pass.<br>• 50.5 drums and a past delivery date are refused.<br>• Over-receipt is refused.<br>• PVC expiry comes from its shelf life. |
| Templates, IQC | Pass.<br>• Copper HCR-H101 passed.<br>• HCR-H102 failed OXIDE (critical): rejected, and NCR-1 raised.<br>• Conditional acceptance needs a justification. |
| Manufacturing set-up | Pass.<br>• A duplicate work centre is refused.<br>• A duplicate BOM line is refused (F3 holds).<br>• A product isn't offered in its own BOM.<br>• A routing operation at another plant's work centre is refused. |
| Work orders (wire) | Pass.<br>• Planned dates are required.<br>• IPQC-WIRE failed THICK and was conditionally accepted.<br>• Receiving 2950 of 3000 m needs a reason for the shortfall.<br>• Dispatch waits for FQC.<br>• Closed short. |
| Work orders (coils) | Pass after G1.<br>• 20.5 coils are refused.<br>• Issue takes only inspected wire (PB-2026-00001, 2849.5 m free).<br>• Receiving waits with "Operation 10 has 2 marked for rework that is not on a rework order yet. Raise one for them first." |
| Rework and reinspection | Pass after G1.<br>• 3 of 2 is refused: "Operation 10 has only 2 marked for rework that is not on a rework order yet, so 3 cannot be."<br>• A work centre at another plant is refused.<br>• Steps must be done before Complete.<br>• The inspector was notified and reinspected with IPQC-COIL90 (preselected); the rework order closed on it.<br>• All 20 coils received; the order completed in full. |
| Final inspection, dispatch | Pass.<br>• A future date, more than is available (2951 m), and 10.5 coils are refused.<br>• 100.5 m and 10 coils were dispatched. |
| Stock transfer | Pass.<br>• 2.5 coils and more than is available are refused.<br>• The received batch takes a "-T1" number; a second Receive is not offered. |
| Traceability | Pass.<br>• Searching by supplier batch or by material finds the lots.<br>• Copper lot HCR-H101 traces forward through wire and coils to both customers, with "Shipment not confirmed" flagged.<br>• The transferred batch traces back to the copper lot. |
| Recall | Pass after G2.<br>• Started from the wire batch, it reaches the coils and the transferred coils, and dispatch of any of them is refused.<br>• A QM override with a reason dispatched 2 coils, and added that dispatch to the recall.<br>• Customer status: attempt, acknowledgement, then resolved. Resolving with goods still out asks why, quoting "100.5 M · 2 PCS still with this customer".<br>• Closure with goods outstanding needs the acceptance box and a note.<br>• A second QM approved the closure. |
| Calibration | Pass. A duplicate ID (ignoring case) and an interval of 0 days are refused. Overdue MIC-01 isn't offered on an inspection; KLV-01 was recorded against the drum inspection. |
| Downtime | Pass.<br>• A future start is refused, except for planned maintenance.<br>• An overlap on the same machine is refused, naming the existing stop (F8 holds).<br>• An end before the start is refused. |
| NCR to CAPA | Pass, the full lifecycle.<br>• NCR-1: assigned, containment recorded, CAPA raised.<br>• CAPA-1: analysis can't finish empty; implementation needs an action. The action was completed and submitted; the QM verified it effective and closed the CAPA.<br>• NCR-1: root cause recorded, verification requested, closed by the QM. |
| Internal audit | Pass, the full lifecycle.<br>• Checklist questions with clauses; audit planned and started.<br>• Completion is refused while questions are unanswered ("3 checklist question(s) have no answer."), and while a nonconforming answer has no finding.<br>• A finding can't be recorded against a conforming answer.<br>• The finding raised NCR-6, and closure waits for it ("NCR-2026-00006 is still open.").<br>• A second QM approved the closure. |
| Accessibility | Pass. No axe violations on 8 pages. |

---

## 6. Fix status

Both issues are fixed on branch `fix/round-7-bugs`. After each fix, the images were rebuilt and the run restarted from signup. Both were re-tested in the browser on the final run.

Checks:
- **Backend:** `mvn verify` ran 209 unit tests and 790 integration tests.
  - One test failed when run just after midnight IST, `AdvancedManufacturingIT` (downtime summary). It counts today's stops, and a stop from an hour earlier fell on the previous day. Its window now starts the day before; this was a test-only change, and the test now passes.
  - New: a test in `ProductionQualityIT` and one in `RecallIT`; updated assertions in `ProductionCompletionIT` and `RecallPdfSamplesTest`.
- **Frontend:** tsc and eslint are clean, and vitest has 225 tests passing.

| ID | Fix | Re-tested |
|---|---|---|
| G1 | **Receiving waits while the last operation has goods marked for rework that are not yet on a rework order:** "Operation 10 has 2 marked for rework that is not on a rework order yet. Raise one for them first."<br>**Once finished goods are received, an operation's rework can't be raised:** "The finished goods of … are received already, so goods reworked from operation 10 could not join them."<br>**Clearer message for raising more than is marked:** "Operation 10 has only 2 marked for rework that is not on a rework order yet, so 3 cannot be." | WO-2026-00002: receipt was blocked with the message. RW-2026-00001 was raised, reworked and reinspected with IPQC-COIL90, then closed. All 20 coils were received and the order completed. |
| G2 | **Recall lines carry their unit.** A dispatch is split by unit.<br>**Totals are per unit** on the recall page, dashboard, customer list, report and PDF: "100.5 M · 12 PCS".<br>**The recovered percentage is shown only when one unit is involved**, and "–" otherwise. | RCL-2026-00001:<br>• "Dispatched 100.5 M · 12 PCS"<br>• Coastal Builders: "100.5 M · 2 PCS still out"<br>• Sahyadri Electricals: "10 PCS"<br>• The report summary reads "100.5 M · 12 PCS dispatched, 0 M · 0 PCS returned, 100.5 M · 12 PCS outstanding." |

Still open from earlier rounds: D18 (checking output against issued material needs a yield rule per product) is waiting on a product decision.
