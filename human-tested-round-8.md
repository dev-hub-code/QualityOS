# Human testing report, round 8: QualityOS

**Date:** 27 Nov 2026
**Build:** Docker images built from `dev` at 87a96b4, which includes the round 7 fixes. The round 8 fixes are in the working tree (not yet committed). After each fix, the images were rebuilt, the fix was re-tested in the browser, and testing continued from where it stopped.
**How it was tested:** In a real Chromium browser, through the UI, the way people use it. Each user had their own browser profile and login, and each step was done by the role that does it in a real plant. The API and database were used only to read back what was stored.

**What is new in this round:**
- **A pharmaceutical oral-liquids maker:** Narmada Life Sciences. Its plants are Ankleshwar in Gujarat, Baddi in Himachal Pradesh and Verna in Goa.
- **Process (recipe) manufacturing in litres:** a 1000 L batch of dextromethorphan syrup made from an API and excipients in KG and LTR, with a 5000 ML flavour line converted to 5 L.
- **Process measurements on the shop floor:** mixing temperature and pH recorded against an operation, with an out-of-limits value needing a comment and alerting the quality engineer.
- **Pharma checks:** assay, pH, viscosity and total aerobic microbial count (TAMC), with an HPLC recorded against an inspection.
- **A Class I recall reported to CDSCO**, with the regulator's reference and a 2-day acknowledgement window, closed with goods still at customers.
- **A customer return that failed its inspection**, and a work order for a product with no approved recipe.

---

## 1. Summary

| | |
|---|---|
| Workflows tested | Signup and invitations, facilities, master data and supplier approval, purchasing, receiving, templates and IQC, recipes and routings, work orders, shop-floor measurements, IPQC and FQC, dispatch, customer returns, stock transfer, traceability, recall with regulator details, calibration, downtime, NCR to CAPA, GMP audit, reports, analytics, billing |
| Not tested this round | Discrete BOMs and rework orders (round 7 covered both; no code in them changed) |
| Users | 23: an owner, then 2 per role (11 roles) |
| Pages opened by URL | 47 pages each as a Viewer, an Auditor, an Operator, an Inspector and a Supplier Manager |
| Accessibility | axe (WCAG 2.1 AA) on 8 pages: return, reports, analytics, recipe, work order, recall, inspections and dispatches. All clean. |
| Issues found | **1 medium, 1 low** (H1, H2). Both are fixed and re-tested in the browser. |
| Restarts | None in full. After H1, a restart from signup was stopped at your request; testing continued from stage 15 on the same organisation. |

---

## 2. Issues

### Medium

| ID | Issue | Where | How to reproduce | Evidence |
|---|---|---|---|---|
| **H1** | **Dispatched, returned and transferred quantities showed no unit.** <br>• The dispatch list read "PB-2026-00001 × 400". Nothing said whether that was 400 litres or 400 bottles. <br>• The same was true on the batch trace, the customer page, the return detail, stock transfers, and the batch and lot pickers. <br>• The return dialog's "X dispatched · Y still with the customer" added lines in different units into one number. | Dispatches, Returns, Stock transfers, Traceability, Customers | Dispatch syrup in litres and kits in pieces on one dispatch. Open the dispatch list, then Record return. | "PB-2026-00001 × 400" (seen). The return summary added every line's quantity regardless of unit, so 40.5 L and 5 PCS would read "45.5 dispatched" (found in the code; this round's dispatches were all in litres). |

### Low

| ID | Issue | Where | How to reproduce | Evidence |
|---|---|---|---|---|
| **H2** | **The recall closure refusal named unresolved customers but not how to settle them.** A customer marked "Unreachable" still counts as unresolved. It needs "Record exception" or "Resolved", and the message did not say so. | Recall, Request closure | Mark a customer Unreachable, tick "I accept that goods remain", then request closure. | "This recall cannot close yet: Customers still unresolved: Hindcare Distributors, Malabar Drug Distributors." |

### Observations (not bugs)

- **One customer return raised two NCRs:**
  - NCR-4 for the customer's complaint ("Crystals at the bottom of drums").
  - NCR-5 when the return inspection failed TAMC.

  These are separate events, so this is by design.
- **Production efficiency showed 2608.7%.** The routing's standard time is about 140 minutes, but the test ran its operations within minutes. Real shop-floor timing gives a sensible figure.
- **A work order for a product with no approved recipe can be submitted and approved.** Only Release is refused ("SYR-PED has no approved recipe in effect on…"). This lets planning go ahead while the recipe is in approval.
- **The return inspection offers the final template (FQC-SYR).** No separate return template is needed for bulk syrup.
- **Resolving a customer who still holds goods asks why** ("380 LTR still with this customer. Why is it resolved without them?"). This is a good guard, noted for the record.
- **Test-script error, not an app bug:**
  - My script confirmed shipment of DSP-2026-00003 (50 L "by mistake") before it could be reversed, so it was returned in full as RET-2026-00002.
  - Reversing an unshipped dispatch was tested instead on DSP-2026-00004.
- **`/ncrs/new` shows "This record does not exist."** This is unchanged from rounds 6 and 7.

---

## 3. Test accounts and data

All passwords are the test password used in earlier rounds. Email addresses are on `narmada.test`.

| Role | Users |
|---|---|
| Organisation Owner | Devendra Trivedi |
| Organisation Admin | Nandini Joshi, Aamir Sheikh |
| Plant Manager | Girish Patel, Lakshmi Iyer |
| Production Manager | Rohan Kulkarni, Mehul Shah |
| Production Operator | Bharat Solanki, Sunita Yadav |
| Production Planner | Kunal Mehta, Priya Nair |
| Quality Engineer | Ashwin Rao, Fatima Siddiqui |
| Quality Inspector | Tejas Vora, Kavita Thakur |
| Quality Manager | Sanjay Bhatt, Radhika Menon |
| Supplier Manager | Jignesh Parmar, Swati Deshpande |
| Viewer | Harish Negi, Pallavi Gupta |
| Auditor | Vikram Chauhan, Shreya Kapoor |

**Data created:**
- **Organisation:** Narmada Life Sciences (Narmada Life Sciences Pvt Ltd), Process manufacturing model.
- **Plants:** ANK-1 Ankleshwar Liquids Plant (Gujarat), BDI-1 Baddi Formulations (Himachal Pradesh), GOA-1 Verna Packing Unit (Goa).
- **Suppliers:**
  - DAL (Deccan API Labs)
  - SSR (Shree Sugar Refiners)
  - AFC (Aroma Flavours Co)
  - GPC (Gujarat Pharma Chemicals)
- **Materials:**
  - Dextromethorphan HBr DXM and sucrose SUCROSE, in KG.
  - Glycerin GLYC and flavour FLAV, in LTR.
  - Sodium benzoate SODB, in KG, with no inspection required.
- **Products:** SYR-DX (dextromethorphan syrup 15 mg/5 ml, bulk) and SYR-PED (paediatric cough syrup, bulk), in LTR.
- **Definitions:**
  - Recipe SYR-DX r1 for a 1000 L batch, with process parameters.
  - Routing SYR-DX: op 10 "Dissolve and mix", op 20 "Filter and hold".
  - Work centres WC-MIX and WC-FIL at Ankleshwar, and WC-BDI at Baddi.
  - Machines MV-01, MV-02 and FP-01.
- **Templates:**
  - Incoming: IQC-DXM, IQC-SUC, IQC-FLAV, IQC-GLYC.
  - In-process: IPQC-SYR.
  - Final: FQC-SYR.
- **Purchasing:** PO-2026-00001 to -00005; GRN-2026-00001 to -00005; LOT-2026-00001 to -00007.
- **Inspections:** INS-2026-00001 to -00009.
  - DXM lot DAL-B12 failed assay (96.5%) and was rejected.
  - The flavour was conditionally accepted.
  - The glycerin was sent back for a note.
  - The return inspection INS-8 failed TAMC and was rejected.
- **Quality:**
  - NCR-2026-00001 (DXM assay), closed through CAPA-2026-00001, which was verified effective.
  - NCR-2026-00002 (flavour, conditional acceptance).
  - NCR-2026-00004 and -00005 (customer return).
  - NCR-2026-00006 (audit finding, open).
- **Production:**
  - WO-2026-00001: 1000 L of SYR-DX. 985 L was received into PB-2026-00001 with a reason for the shortfall, and 15 L scrapped. FQC accepted it.
  - WO-2026-00002: 500 L of SYR-PED, cancelled because no recipe was approved.
  - Batch PB-2026-00001-T1: 100 L at Baddi.
- **Dispatch:**
  - Customers MDD (Malabar Drug Distributors, Kerala) and HCD (Hindcare Distributors, Delhi).
  - DSP-2026-00001: 400 L to MDD, of which 20 L was returned as RET-2026-00001 and rejected.
  - DSP-2026-00002: 300 L to HCD.
  - DSP-2026-00003: 50 L to HCD, returned in full as RET-2026-00002.
  - DSP-2026-00004: 10 L from Baddi to MDD, reversed before shipment.
- **Recall:** RCL-2026-00001, Class I, reported to CDSCO (CDSCO/RCL/2026/0412), closed.
  - Malabar acknowledged and was resolved, with 380 L still out and explained.
  - Hindcare was unreachable and has an exception recorded.
  - A second QM approved the closure.
- **Audit:** checklist GMP-LIQ (Schedule M clauses); AUD-2026-00001 (process, Ankleshwar), completed, with closure waiting on NCR-6.
- **Other:**
  - Stock transfer STR-2026-00001: 100 L, Ankleshwar to Baddi.
  - Instruments: BAL-01 (overdue) and HPLC-01.
  - Downtime on MV-01 and FP-01.

---

## 4. Role-based testing

- **Maker/checker held throughout:**
  - Suppliers: the second supplier manager approves; a rejection needs a reason and notifies the submitter.
  - Templates: written by the QE, published by the QM.
  - Recipes and routings: the author cannot approve their own; the second production manager approved.
  - Work orders: the planner raises and submits; the production manager approves and releases. Approve and Release are not offered to the planner.
  - CAPA effectiveness and NCR closure are by the QM. The QE, who did the work, has no Verify or Close button.
  - Recall closure: the QM who asked for it has no Approve. The second QM approved.
- **Who acts where:**
  - Operators start and report operations and record process measurements.
  - Inspectors run IQC, IPQC, FQC and the return inspection.
  - Production managers dispatch, record returns and send transfers.
  - Recalled stock, including the transferred batch, cannot be dispatched by them.
- **Pages opened by URL:** 47 pages for each of five roles. Every page a role may not use shows "You do not have permission to do this." No hidden page offered an action. The Supplier Manager can open the new purchase order form, which their role allows.
- **Notifications seen:**
  - PROCESS_OUT_OF_LIMITS (QE)
  - SUPPLIER_REJECTED (submitter)
  - INSPECTION_DISPOSITION_RECORDED (inspector)
  - CAPA_AWAITING_VERIFICATION and NCR_AWAITING_CLOSURE (QM)

---

## 5. Workflows and scenarios

| Workflow | Result |
|---|---|
| Signup, invitations | Pass. Signup guards held; 22 invitations accepted. |
| Facilities | Pass.<br>• Goa with a Gujarat GSTIN was refused.<br>• Wrong state codes and check characters were refused.<br>• Departments, shifts and holidays were saved. |
| Master data, suppliers | Pass.<br>• A supplier rejection needs a reason and notifies the submitter.<br>• A spec with minimum above maximum was refused.<br>• DXM (KG) in ML was refused. |
| Purchase orders, receiving | Pass.<br>• A past delivery date and a zero quantity were refused.<br>• Over-receipt was refused.<br>• An expired supplier batch was refused, with the date written out.<br>• Expiry comes from shelf life.<br>• SODB shows "not required" for inspection. |
| Templates, IQC | Pass.<br>• DXM DAL-B12 failed assay (critical): rejected, and NCR-1 raised.<br>• The flavour was conditionally accepted (NCR-2).<br>• A send-back needs a note. |
| Recipes and routings | Pass.<br>• Refused: a batch size of 0, sucrose in LTR, a duplicate line, limits the wrong way round, and a routing step at another plant.<br>• The product isn't offered in its own recipe.<br>• 5000 ML flavour is converted to 5 L.<br>• Self-approval is blocked. |
| Work orders | Pass.<br>• The planner cannot approve or release.<br>• The rejected DXM lot isn't offered for issue.<br>• SYR-PED with no approved recipe: Release refused, then the order was cancelled. |
| Shop floor | Pass.<br>• TEMP 48 °C (limits 35–45) needs a comment, and the QE is alerted.<br>• "abc" is refused.<br>• Op 20 with 985 good, 15 rejected and 15 scrap is refused: "Good, rejected and scrap make 1015, not 1000." |
| IPQC, FQC, receipt | Pass.<br>• IPQC-SYR is preselected.<br>• Receiving 985 of 1000 L needs a reason.<br>• Dispatch waits for FQC. |
| Dispatch | Pass after H1.<br>• A future date and more than is available are refused.<br>• Quantities show units: "PB-2026-00001 × 400 LTR".<br>• An unshipped dispatch (DSP-4) was reversed, and its 10 L made available again. |
| Customer returns | Pass after H1.<br>• Not offered before shipment.<br>• 401 L against 400 L is refused.<br>• An expected return with a complaint raises NCR-4.<br>• A future received date is refused.<br>• The return inspection failed TAMC and was rejected, raising NCR-5; the 20 L was not restocked. |
| Stock transfer | Pass.<br>• More than is available was refused ("only 235 available", which is 985 − 750 dispatched).<br>• 100 L was received at Baddi as PB-2026-00001-T1. |
| Traceability | Pass. Searching by supplier batch (DAL-B11) or material name finds the lots. DAL-B11 traces forward to both customers, with "Shipment not confirmed" where it applies. |
| Recall | Pass after H2.<br>• Class I, CDSCO, with the regulator's reference and a 2-day acknowledgement window.<br>• Dispatch of recalled stock, including T1, was refused.<br>• Contact attempt, acknowledgement and an exception for the unreachable customer.<br>• Closed with goods remaining, explained in the note, and approved by a second QM. |
| Calibration | Pass.<br>• A duplicate ID (ignoring case) and an interval of 0 days were refused.<br>• The overdue BAL-01 isn't offered on an inspection; HPLC-01 was recorded. |
| Downtime | Pass.<br>• A future breakdown was refused, since only planned maintenance can be entered ahead.<br>• An overlapping stop on MV-01 was refused, naming the existing stop.<br>• An end before the start was refused. |
| NCR to CAPA | Pass, the full lifecycle. NCR-1 was assigned and contained, and CAPA-1 raised. Analysis can't finish empty. The CAPA was implemented and verified effective by the QM, then both were closed. |
| GMP audit | Pass. GMP-LIQ has Schedule M clauses; the audit was planned, started and answered (conforming, nonconforming, N/A). A major finding raised NCR-6. Closure is refused while NCR-6 is open. |
| Reports, analytics, billing | Pass.<br>• 12 reports, each with PDF and Excel downloads.<br>• Analytics, production analytics, control charts and the dashboard showed no errors.<br>• Billing shows 23 of 50 users, 3 of 5 facilities, and 9 inspections this month. |
| Accessibility | Pass. No axe violations on 8 pages. |

---

## 6. Fix status

Both issues are fixed in the working tree (not yet committed). After each fix, the images were rebuilt and the fix was re-tested in the browser on the round 8 organisation.

Checks:
- **Backend:** `mvn verify` ran 209 unit tests and 791 integration tests, all passing with H1. RecallIT (32 tests) passes with H2.
  - New: `RoundEightFixesIT`.
  - Updated assertions: `StockTransferIT` and `RecallIT`.
- **Frontend:** tsc and eslint are clean, and vitest has 225 tests passing.

| ID | Fix | Re-tested |
|---|---|---|
| H1 | **Dispatch lines, return items and transfer lines carry their unit**, and every screen shows it: dispatch list, trace, customer page, return detail, stock transfers, the "up to" figure in the return dialog, and the batch and lot pickers.<br>**The return summary is per unit:** "40.5 LTR · 5 PCS dispatched".<br>**The return NCR description names the unit.** | • "PB-2026-00001 × 400 LTR"<br>• "Returned from PB-2026-00001 (up to 400 LTR)"<br>• "400 LTR dispatched · 400 LTR still with the customer"<br>• "LOT-2026-00004 – FLAV (15 LTR free)"<br>• "PB-2026-00001 × 100 LTR (SYR-DX) → PB-2026-00001-T1" |
| H2 | **The refusal says how to settle a customer:** "Customers still unresolved: …. Mark each one resolved, or record an exception for one you cannot settle, such as a customer you cannot reach." | RCL-2026-00001: the new message was shown. An exception was recorded for Hindcare and Malabar was resolved; closure was then requested and approved. |

Still open from earlier rounds: D18 (checking output against issued material needs a yield rule per product) is waiting on a product decision.
