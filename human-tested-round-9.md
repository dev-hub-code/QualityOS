# Human testing report, round 9: QualityOS

**Date:** 28 Nov 2026
**Build:** Docker images built from `dev` at 41ea569, which includes unit conversions phase 1 (PR #189). The round 9 fixes are in the working tree (not yet committed). After each fix, the images were rebuilt, the fix was re-tested in the browser, and testing continued from where it stopped.
**How it was tested:** In a real Chromium browser, through the UI, the way people use it. Each user had their own browser profile and login, and each step was done by the role that does it in a real plant. The API and database were used only to read back what was stored.

**What is new in this round:**
- **A paints and coatings maker:** Tapti Coatings. Its plants are Taloja in Maharashtra and Pithampur in Madhya Pradesh, with a depot at Raipur in Chhattisgarh.
- **Item unit conversions through a whole chain, for the first time:**
  - Titanium dioxide is kept in KG and bought in 25 kg bags.
  - Calcium carbonate is kept in KG and bought in 50 kg bags. A bag is sized per item.
  - Alkyd resin is kept in KG, bought in 200 kg drums, and dosed in litres at 0.96 kg/L.
  - Turpentine is kept in litres, bought in 200 L drums, and dosed in KG at 1.25 L/kg.
- **Orders, receipts, recipes, issue and costing in those units:**
  - Orders were placed in drums and bags.
  - Receipts were counted in drums, litres and kilograms on one delivery.
  - The recipe has resin in litres and turpentine in KG.
  - Issue and costing convert each through the item's own rule.
- **Paint checks:**
  - Flash point (°C), non-volatile content and acid value.
  - Fineness of grind (Hegman) and Ford cup viscosity.
  - Gloss at 60° and surface dry time.
- **A Class II recall reported to BIS**, and a work order planned for a day before its recipe took effect.

---

## 1. Summary

| | |
|---|---|
| Workflows tested | Signup and invitations, facilities, master data and supplier approval, **units of measure and item conversions**, purchasing and receiving in other units, templates and IQC, recipes and routings, work orders, shop-floor measurements, IPQC and FQC, dispatch, customer returns, stock transfer, traceability, recall with regulator details, calibration, downtime, NCR to CAPA, process audit, reports, analytics, billing |
| Not tested this round | Discrete BOMs and rework orders (round 7 covered both). Conversions on BOM lines are covered by the integration tests. |
| Users | 23: an owner, then 2 per role (11 roles) |
| Pages opened by URL | 47 pages each as a Viewer, an Auditor, an Operator, an Inspector and a Supplier Manager |
| Accessibility | axe (WCAG 2.1 AA) on 8 pages: return, reports, analytics, recipe, work order, recall, inspections and dispatches. All clean. |
| Issues found | **3 medium, 2 low** (H1 to H5). All are fixed and re-tested in the browser. |
| Restarts | None. Testing continued after each fix on the same organisation. |

---

## 2. Issues

### Medium

| ID | Issue | Where | How to reproduce | Evidence |
|---|---|---|---|---|
| **H2** | **The conversions panel offered units no stock is counted in.** <br>• Readings such as °C, %, HB and HRC were offered for titanium dioxide, as if "1 °C = 5 KG" could mean something. <br>• Units already reached through a rule were offered too, such as GM on turpentine once 1 KG = 1.25 LTR existed. The server then refused them. | Material or product page, Unit conversions | Open TIO2 and look at "1 of unit". | "TIO2 units offered: PCS, HB, HRC, CM, M, MM, BAG, DRUM, PAIL, %, °C, LTR, ML, TIN4" |
| **H3** | **The over-receipt refusal gave no unit and no total.** The clerk counted 2 drums plus 330 KG of turpentine (kept in litres). The message named neither the unit nor what the batches came to. | Receive material | On an 800 LTR order, receive 2 DRUM and 330 KG on one receipt. | "Line 1: only 800 remains to be received." |
| **H4** | **The inspection page showed the lot's quantity with no unit.** It read "400", which could be litres of turpentine or kilograms. | Inspection detail | Open INS-2026-00001. | "Quantity 400" |

### Low

| ID | Issue | Where | How to reproduce | Evidence |
|---|---|---|---|---|
| **H1** | **A bad conversion factor gave a technical message.** A factor of 0 or -2 showed "Request validation failed". "abc" or "1,5" showed "The request body could not be read." Neither said what to type. | Unit conversions | On MTO, add 1 PAIL = 0. | Both messages seen. |
| **H5** | **Release said no recipe was in effect, but not that one was about to be.** WO-1 was planned to start 27 Nov. The recipe was approved on 28 Nov and took effect that day. The refusal did not mention the approved revision or that rescheduling fixes it. | Work order, Release | Plan an order for yesterday, approve its recipe today, then release. | "ENM-WHT has no approved recipe in effect on 27 Nov 2026." |

### Observations (not fixed)

- **O1: Shop-floor messages give no unit.**
  - "Operation 10 can take 1000 more: the order is for 1000."
  - "Good, rejected and scrap make 1015, not 1000."
  - "Only 985 of 1000 were made."

  The page around them shows LTR, so these are less confusing than H3 or H4. They are the next candidates for the same treatment.
- **O2: A recall contact attempt accepts a follow-up date earlier than the attempt.** My script typed 27 Nov for an attempt on 28 Nov, and the app saved it.
- **O3: A breakdown entered by hand with no end leaves its machine "available".** LD-01's breakdown from 26 Nov, entered afterwards, counted 54 hours. Operation 20 had already run on LD-01 that day. This is a result of the order the script entered things in, recorded for review.
- **The work order shows material in the stock unit.** The recipe line "450 LTR" of resin shows as "432 KG" on the work order. This is correct, but "450 LTR (432 KG)" would match the purchase order's style.
- **Packs with no factor allow fractions.** BAG and DRUM are made without a factor (kind "Other"), so 10.5 bags is accepted. A pack of fixed size per item, such as 25 kg, would need a "whole packs" setting.
- **Removing a conversion checks orders, recipes, material lists and work orders, but not receipts.** Receipts keep both what was typed and the stock quantity, so nothing changes meaning. Only the "2 DRUM (400 LTR)" label would lose its unit if the rule were removed.
- **Production efficiency showed 20000%.** The test ran operations within minutes, the same as in round 8.
- **Script errors, not app bugs:**
  - The order dates came from round 8.
  - The instrument step looked for round 8's HPLC-01.
  - The measurements were tried before the operation was started. Measuring rightly needs a started operation.

---

## 3. Test accounts and data

All passwords are the test password used in earlier rounds. Email addresses are on `tapti.test`.

| Role | Users |
|---|---|
| Organisation Owner | Sudhir Gokhale |
| Organisation Admin | Ketaki Bapat, Imran Qureshi |
| Plant Manager | Prakash Wagh, Shalini Verma |
| Production Manager | Nitin Pawar, Arjun Saxena |
| Production Operator | Dilip Jadhav, Rekha Sahu |
| Production Planner | Omkar Bhosale, Neha Dubey |
| Quality Engineer | Farhan Ansari, Gauri Joshi |
| Quality Inspector | Sachin Mane, Pooja Tiwari |
| Quality Manager | Manoj Chitale, Anjali Rathore |
| Supplier Manager | Yogesh Patil, Varsha Kale |
| Viewer | Ravi Shukla, Seema Agarwal |
| Auditor | Deepak Naik, Tanvi Gadgil |

**Data created:**
- **Organisation:** Tapti Coatings (Tapti Coatings Pvt Ltd), Process manufacturing model.
- **Plants:** TLJ-1 Taloja Paints Plant (Maharashtra), PTH-1 Pithampur Coatings Plant (Madhya Pradesh), RPR-1 Raipur Depot (Chhattisgarh).
- **Suppliers:**
  - SCS (Sabarmati Chemicals and Solvents)
  - VPI (Vasant Pigments)
  - RAD (Ramanathan Additives)
  - KRC (Kanpur Resin Company), rejected once and then resubmitted
- **Units added:** BAG, DRUM and PAIL (no factor), and TIN4 (1 TIN4 = 4 LTR).
- **Materials and their conversions:**

  | Code | Material | Kept in | Conversions |
  |---|---|---|---|
  | TIO2 | Titanium dioxide, rutile | KG | 1 BAG = 25 KG |
  | CACO3 | Calcium carbonate | KG | 1 BAG = 50 KG |
  | ALKYD | Long-oil alkyd resin | KG | 1 DRUM = 200 KG, 1 LTR = 0.96 KG |
  | MTO | Mineral turpentine oil | LTR | 1 DRUM = 200 LTR, 1 KG = 1.25 LTR |
  | DRIER | Cobalt octoate drier | KG | none; no inspection required |
- **Products:** ENM-WHT (synthetic enamel, brilliant white, bulk) and PRM-RO (red oxide primer, bulk), in LTR. ENM-WHT had 1 PAIL = 20 LTR, which was later removed.
- **Definitions:**
  - Recipe ENM-WHT r1 for a 1000 L batch:
    - TIO2 250 KG
    - CACO3 100 KG
    - ALKYD 450 LTR
    - MTO 120 KG
    - DRIER 2500 GM
  - Process parameters: grinding time, batch temperature and fineness of grind.
  - Routing ENM-WHT: op 10 "Premix and grind", op 20 "Let down and tint".
  - Work centres WC-GRD and WC-LTD at Taloja, and WC-PTH at Pithampur.
  - Machines BM-01, BM-02 and LD-01.
- **Templates:**
  - Incoming: IQC-MTO, IQC-TIO2, IQC-ALK, IQC-CAC.
  - In-process: IPQC-ENM.
  - Final: FQC-ENM.
- **Purchasing:**

  | Order | Material | Ordered | Received |
  |---|---|---|---|
  | PO-2026-00001 | MTO | 4 DRUM (800 LTR) | 2 DRUM + 320 KG = 800 LTR |
  | PO-2026-00002 | TIO2 | 40 BAG (1000 KG) | 40 BAG |
  | PO-2026-00003 | ALKYD | 4 DRUM (800 KG) | 3 DRUM + 200 LTR = 792 KG |
  | PO-2026-00004 | CACO3, DRIER | 20 BAG, 10 KG | 20 BAG, 10 KG |
  | PO-2026-00005 | MTO | 2 DRUM | 2 DRUM |

  GRN-2026-00001 to -00005; LOT-2026-00001 to -00008.
- **Inspections:** INS-2026-00001 to -00010.
  - MTO lot SCS-M2 failed flash point (31 °C, minimum 35) and was rejected.
  - CACO3 moisture led to conditional acceptance.
  - ALKYD was sent back for a note.
  - The return inspection INS-9 failed gloss and dry time and was rejected.
- **Quality:**
  - NCR-2026-00001 (flash point), closed through CAPA-2026-00001, which was verified effective.
  - NCR-2026-00002 (CACO3, conditional acceptance).
  - NCR-2026-00004 and -00005 (customer return).
  - NCR-2026-00006 (audit finding, open).
- **Production:**
  - WO-2026-00001: 1000 L of ENM-WHT, rescheduled to 28 Nov. 985 L was received into PB-2026-00001, 15 L scrapped. FQC accepted it.
  - Material cost was ₹145,735, from prices per bag and per drum.
  - WO-2026-00002: 500 L of PRM-RO, cancelled because no recipe was approved.
  - Batch PB-2026-00001-T1: 100 L at Pithampur.
- **Dispatch:**
  - Customers HPD (Hubli Paint Depot, Karnataka) and NPM (Nizam Paint Mart, Telangana).
  - DSP-2026-00001: 400 L to HPD, of which 20 L was returned as RET-2026-00001 and rejected.
  - DSP-2026-00002: 300 L to NPM.
  - DSP-2026-00003: 50 L to NPM, not shipped.
- **Recall:** RCL-2026-00001, Class II, reported to BIS (BIS/CMD/2026/0118), closed.
  - Hubli acknowledged and was resolved.
  - Nizam was unreachable and has an exception recorded.
  - A second QM approved the closure.
- **Audit:** checklist PNT-PROC; AUD-2026-00001 (process, Taloja), completed, with closure waiting on NCR-6.
- **Other:**
  - Stock transfer STR-2026-00001: 100 L, Taloja to Pithampur.
  - Instruments: GLM-01 (overdue) and VSC-01.
  - Downtime on BM-01 and LD-01.

---

## 4. Role-based testing

- **Maker/checker held throughout:**
  - Suppliers: the second supplier manager approves; a rejection needs a reason.
  - Templates: written by the QE, published by the QM.
  - Recipes and routings: the second production manager approved.
  - Work orders: the planner cannot approve or release.
  - CAPA verification and NCR closure are by the QM only.
  - Recall closure: approved by the second QM.
- **Units and conversions are managed under Product management.** The admin added units and conversions. Rescheduling the work order was done by the planner.
- **Pages opened by URL:** 47 pages for each of five roles. Every page a role may not use shows "You do not have permission to do this." No hidden page offered an action.
- **Notifications seen:**
  - SUPPLIER_REJECTED and SUPPLIER_AWAITING_APPROVAL
  - PROCESS_OUT_OF_LIMITS (QE)
  - INSPECTION_CRITICAL_FAILURE, CAPA_AWAITING_VERIFICATION and NCR_AWAITING_CLOSURE (QM)
  - INSPECTION_DISPOSITION_RECORDED (inspector)

---

## 5. Workflows and scenarios

| Workflow | Result |
|---|---|
| Signup, invitations | Pass. Signup guards held; 22 invitations accepted. |
| Facilities | Pass.<br>• Raipur (Chhattisgarh) with a Maharashtra GSTIN was refused.<br>• A wrong check character was refused.<br>• Overlapping and zero-length shifts, a break longer than its shift, and a duplicate holiday were refused. |
| Master data, suppliers | Pass.<br>• A Tamil Nadu supplier with a Maharashtra GSTIN was refused.<br>• A rejection needs a reason.<br>• A spec with minimum above maximum was refused.<br>• ALKYD (KG) in ML on a material list was refused before it had a conversion: "Add a unit conversion to ALKYD, such as 1 ML = … KG." |
| Units of measure | Pass.<br>• BAG, DRUM and PAIL were added with no factor, and TIN4 = 4 LTR.<br>• "Drum" was refused as a duplicate of DRUM.<br>• A factor of 0 was refused. |
| Item conversions | Pass after H1 and H2.<br>• Refused: 1 ML = 0.00096 KG on ALKYD ("already converts through LTR"), a second DRUM rule, and TIN4 on the enamel (the same kind as LTR).<br>• Bad factors are refused with what to type.<br>• Readings are not offered.<br>• ALKYD's unit cannot change while it has conversions. |
| Purchase orders | Pass. Lines in DRUM and BAG at a price per drum or bag. The picker offers only units that reach the material: for TIO2, KG, GM and BAG. The order shows "4 DRUM (800 KG)". |
| Receiving | Pass after H3.<br>• The unit is preset to the order line's.<br>• 2 DRUM + 330 KG against 800 LTR was refused with units.<br>• 2 DRUM + 320 KG = 800 LTR was accepted.<br>• 3 DRUM + 200 LTR = 792 KG of ALKYD.<br>• An expired batch was refused.<br>• The receipt shows "2 DRUM (400 LTR)" and "40 BAG (1000 KG)". |
| Templates, IQC | Pass after H4.<br>• MTO SCS-M2 failed flash point (critical): rejected, and NCR-1 raised.<br>• CACO3 was conditionally accepted (NCR-2).<br>• A send-back needs a note.<br>• The inspection shows "Quantity 400 LTR". |
| Recipes and routings | Pass.<br>• ALKYD 450 LTR and MTO 120 KG were saved through their conversions.<br>• CACO3 in LTR was refused with the conversion to add.<br>• A duplicate line, a batch size of 0, reversed limits and a cross-plant routing step were refused.<br>• The product is not offered in its own recipe. |
| Work orders | Pass after H5.<br>• Release names the revision and the fix; the planner rescheduled ("It fits within capacity").<br>• Material was issued from the reserved lots, converted: ALKYD 432 KG, MTO 150 LTR, DRIER 2.5 KG.<br>• PRM-RO with no recipe: Release refused, then the order was cancelled. |
| Costing | Pass. Material cost was ₹145,735, matching the bag and drum prices worked out per stock unit. |
| Shop floor | Pass.<br>• TEMP 62 °C (limits 30–55) needs a comment, and the QE is alerted.<br>• "abc" is refused.<br>• 1001 against 1000 is refused, as is 1015 ≠ 1000. |
| IPQC, FQC, receipt | Pass.<br>• IPQC-ENM is preselected.<br>• Receiving 985 of 1000 L needs a reason.<br>• Dispatch waits for FQC. |
| Dispatch, returns | Pass.<br>• Dispatch: a future date and more than is available are refused.<br>• Returns are not offered before shipment, and 401 against 400 is refused.<br>• A complaint raised NCR-4.<br>• The return inspection failed and was rejected (NCR-5). |
| Stock transfer, traceability | Pass.<br>• "Only 235 available" (985 − 750 dispatched).<br>• 100 L arrived at Pithampur as PB-2026-00001-T1.<br>• SCS-M1 and "turpentine" both find the lot, which traces to both customers. |
| Recall | Pass.<br>• Class II, BIS, with a 2-day acknowledgement window.<br>• Dispatch of recalled stock was refused.<br>• Acknowledgement and resolution for Hubli, and an exception for Nizam.<br>• Closure is refused while customers are unresolved; it was then requested and approved by a second QM. |
| Conversions after use | Pass.<br>• Removing MTO's DRUM rule is refused ("entered in DRUM on purchase orders").<br>• Removing ALKYD's LTR rule is refused ("on BOMs or recipes").<br>• ENM-WHT's unused PAIL rule was removed.<br>• ENM-WHT's unit cannot change. |
| Calibration, downtime | Pass.<br>• A duplicate ID and 0 days were refused.<br>• The overdue GLM-01 is not offered on an inspection; VSC-01 was recorded.<br>• Downtime: future, overlapping and end-before-start stops were refused. |
| NCR to CAPA | Pass, the full lifecycle. NCR-1 → CAPA-1, verified effective, then both closed. |
| Process audit | Pass. A finding raised NCR-6; closure is refused while it is open. |
| Reports, analytics, billing | Pass.<br>• 12 reports, each with PDF and Excel downloads. The material rejection report says quantities are in the material's own unit.<br>• Analytics, control charts and the dashboard showed no errors.<br>• Billing shows 3 of 5 facilities. |
| Accessibility | Pass. No axe violations on 8 pages. |

---

## 6. Fix status

All five issues are fixed in the working tree (not yet committed). After each fix, the images were rebuilt and the fix was re-tested in the browser on the Tapti Coatings organisation.

| ID | Fix | Tests | Re-tested |
|---|---|---|---|
| H1 | The panel checks the factor before sending it: "Enter how many LTR one PAIL holds, as a number above zero such as 25 or 0.96." | `item-units.test.tsx` (factorProblem) | 0, -2, "abc" and "1,5" on MTO |
| H2 | The panel leaves out readings and units already reached through a rule. The server refuses a reading: "°C is a reading, such as a temperature or a percentage, not an amount TIO2 can be counted in." The factor box is labelled "Holds". | `item-units.test.tsx` (conversionChoices), `ItemUnitConversionIT` | TIO2 is offered PCS, CM, M, MM, DRUM, PAIL, LTR, ML and TIN4; MTO is not offered GM |
| H3 | "Line 1, MTO: this receipt comes to 812.5 LTR, but only 800 LTR remains to be received." | `PurchasingIT`, `ItemUnitConversionIT` | 10 LTR of ALKYD against 8 KG left: "this receipt comes to 9.6 KG, but only 8 KG remains" |
| H4 | The inspection carries the unit of its lot or batch. | `InspectionIT` | "Quantity 400 LTR", and "1000 LTR" on the IPQC |
| H5 | "… Revision 1 takes effect on 28 Nov 2026: reschedule the order to start on or after that day." The same applies to BOMs and routings. | `WorkOrderIT` | WO-1 page message; rescheduled and released |

Checks:
- **Backend:** `mvn verify` ran 209 unit tests and 807 integration tests, all passing.
  - Updated: `ItemUnitConversionIT`, `PurchasingIT`, `InspectionIT` and `WorkOrderIT`.
- **Frontend:** tsc and eslint are clean, and vitest has 235 tests passing.
