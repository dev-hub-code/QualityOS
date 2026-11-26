# Human testing report, round 4: ProductionOS

**Date:** 26 Nov 2026
**Build:** `dev` at `31a77aa` (after PR #182, the round 3 fixes), local Docker Compose stack (Postgres, Mailpit, backend, frontend)
**How it was tested:** A real Chromium browser, driven through the UI the way people use it. That meant clicking buttons, filling forms, reading on-screen messages and reading emails in Mailpit. Every user had their own browser profile and login, and each step was done by the role that does it in a real plant. Failed API calls and console errors were recorded throughout. The API was called directly only to check that the server enforces the same rules as the UI (permissions, tenant isolation, facility limits).
**What is new in this round:**
- **A fresh organisation in a new industry:** Indrayani Coatings, a paint and resin maker in Pune, with plants in Chakan, Talegaon and Vapi (Gujarat).
- **Two users per role:** makers and checkers were always different people.
- **Bulk and counted goods together:** litres and kilograms in bulk, and pails counted in pieces, which tests units, sampling and conversions.
- **Round 3 fixes rechecked:** every C-series fix was re-checked inside the workflows.

---

## 1. Summary

| | |
|---|---|
| Workflows tested | 22 end-to-end workflows (W1–W22), plus stock transfer, downtime, offline use and a role × page sweep |
| Users | 23: an owner, then 2 per role (11 roles) |
| Role × page sweep | 45 pages × 12 users (one per role) |
| Tenant isolation | Passed. The round 3 organisation gets 404 for every round 4 record, and its own batch PB-2026-00002 resolves to its own data. |
| Accessibility | axe (WCAG 2.1 AA): 12 of 13 pages clean (D24 on the 13th) |
| Issues found | **3 medium, 15 low, 6 cosmetic** (D1–D25; D16 was not used) |
| Round 3 fixes re-checked | C1, C4, C5, C6, C7, C8, C9, C11, C15, C20, C21, C22, C23, C25, C26, C27, C28, C29, C30, C31, C32, C33, C34, C35, C36, C37, C40, C41, C42. All hold, with leftovers noted in D10, D12 and D22. |

**Overall:** Every workflow could be completed by the right roles, and no data was lost or corrupted. The round 3 medium issues are all fixed:
- serial-tracked goods can't leave without serials (C21);
- a stop can't end before it starts (C30);
- a bulk lot isn't AQL-sampled by weight (C5);
- a reinspection after a failed calibration starts by itself (C31).

The three medium issues found this round:
- an approved supplier could be renamed, with a new GSTIN, without a second approval (D7);
- overlapping or zero-length shifts inflated capacity (D2);
- a BOM could be approved with a line in a unit that can never convert, which then blocked every release (D8).

---

## 2. Issues

### Medium

| ID | Issue | Where | How to reproduce | Evidence |
|---|---|---|---|---|
| **D7** | **An approved supplier can change its name and GSTIN without a second approval.** One supplier manager renamed an approved supplier and gave it a new GSTIN. It stayed approved, with no visible history. This gets round the second-approver rule from PR #180. | `SupplierService.update` | Approved TPG → Edit → new name + GSTIN → Save | TPG renamed "Titan Pigments and Dyes", GSTIN 27AABCK5504D1ZY, still approved |
| **D2** | **Shifts are not checked for zero length or overlap.** A 09:00–09:00 shift was saved and counted as 24 h. An "Overlap" shift from 14:00 to 18:00 was saved over shift B. Working minutes were then summed: scheduling showed WC-FILL at 43 h a day, which overstates capacity. | `FacilityService.addShift`, `ShiftCalendar.workingMinutes` | Facility → Shifts → add 09:00–09:00, then 14:00–18:00 next to 14:00–22:00 | CHK-1. The two shifts were removed afterwards |
| **D8** | **A product's material list and its BOM accept a unit of another kind.** TiO2 (kept in KG) was listed at 5.2 LTR, and the BOM was approved. Every release of WO-2 then failed with "A quantity in LTR cannot be converted to KG.", which names no line or material. | Product material list; BOM copy from the list; explosion | Product → Materials → TIO2 5.2 LTR → New BOM from list → approve → release a WO | Worked around with BOM revision 2 |

### Low

| ID | Issue | Where / evidence |
|---|---|---|
| D1 | A GSTIN's state code is not checked against the state given with it. A Gujarat plant (VAP-1) and a Karnataka supplier (GCP) both accepted a Maharashtra GSTIN (27…). The dialog only noted "The GSTIN is registered in …". | Facility and supplier forms |
| D4 | Codes that differ only in case are accepted as different codes. Examples: "visc" next to "VISC", "tio2" next to "TIO2", "wc-disp" next to "WC-DISP", and "ph-01" next to "PH-01". This made the "PH-01" link ambiguous. | Defect categories, materials, work centres, instruments |
| D6 | The submitter sees Reject on their own pending supplier. Using it marks the supplier "rejected" with the submitter's own note. | Supplier page |
| D9 | A PO accepted 10.5 pails, of a material counted in PCS. | PO-2026-00004 |
| D10 | Raw ISO dates (left over from C1) on detail pages, panels, option labels and messages. Examples: PO order date, receipt "Received on", dispatch dates, calibration "Due", the recall date and "Told on", audit "Planned for", the plan header, lot "expires 2027-05-20", the in-doubt panel, the serial page, the recall report, analytics ranges, and "Revision 1 takes effect on 2026-11-26". | Many pages and messages |
| D11 | A template with AQL or percentage sampling, made for a KG/LTR material, is published with no warning. Only starting an inspection refuses it. | IQC-TIO2, IQC-MTO |
| D13 | An AQL yes/no parameter with no expected answer: 4 of 50 lids answered "No" were not counted as nonconforming. The reviewer saw only "Reviewer decides". | INS-2026-00006 → NCR-2 |
| D14 | A manual NCR accepts a batch that doesn't exist ("LOT-9999-00001"). The NCR is then outside every trace. | NCR form |
| D15 | After "Link existing CAPA", the reason is shown nowhere, and the CAPA doesn't list the NCR. | NCR-3 → CAPA-2 |
| D17 | After an "End of shift / hand-over" pause, the next shift's operator has no Resume until a production manager reassigns the operation. | WO-2 op 10 |
| D18 | A pail operation reported 100 processed / 97 good from 1,960 L of bulk issued, which is 98 pails' worth. Nothing checks output against the material issued, beyond the variance shown at receipt. | WO-2 op 20 |
| D20 | Returned goods waiting for inspection are not in the inspection queue ("Nothing is waiting."). Only the return page offers Inspect. | RET-2026-00001 |
| D23 | After a failed calibration, the calibration report leaves LAST CALIBRATED blank. | PH-01, VIS-01 |
| D24 | Production analytics fails axe `definition-list`: a `<p>` and `<div>` groups inside the per-unit `<dl>` (from the C29 change). | /manufacturing/analytics |
| D25 | Pages that render over a refused call. The Supplier Manager on /customers sees "No customers yet." (403 behind it). The Production Manager on /ncrs loads /suppliers (403), leaving an empty supplier picker. The Planner on quality checkpoints loads templates (403). | Role × page sweep |

### Cosmetic

| ID | Issue | Where |
|---|---|---|
| D3 | State holidays are shown "Thu, Jan 14" (US order), but "14 Jan 2027" elsewhere. | /state-holidays |
| D5 | Record dialogs don't check a GSTIN before sending it. The server's message then reads "GSTIN \| is not a valid GSTIN…" (lower case). | Supplier, customer and facility dialogs |
| D12 | The notification text reads "…was closed with the disposition ACCEPT." (raw value, left over from C33). | Notifications |
| D19 | Dispatch Date, Shipped on and Recall date have no default. PO, receipt and transfer dates default to today. | Dispatch, ship and recall dialogs |
| D21 | The regulator PDF shows raw "CLOSED", "RELEASED" and "(CLOSED)", and "Recall started 2026-11-26". | RCL-2026-00001 final report |
| D22 | "1 record(s) used this instrument since its last good calibration" (left over from C4). | Calibration page |

### Observations (not bugs)

- After "Effective", a CAPA still offers Verify effectiveness again.
- An Organisation Admin cannot grant Plant Manager or Quality Manager, because those roles hold permissions the admin lacks. Only the owner can appoint them.
- An urgent BOM fix needs the current revision retired first. Retiring needs a reason. Line numbers are not renumbered (2., 3.).
- Departments are listed PACK, MIX, QC: neither the creation order nor alphabetical.

---

## 3. Test accounts and data

Password for every user: `correct-horse-battery`. Domain: `@indrayani.test`.

| Role | Users |
|---|---|
| Organisation Owner | Meera Joshi |
| Organisation Admin | Farah Khan, Sunil Pawar |
| Plant Manager | Anand Gokhale, Rekha Nair |
| Production Manager | Ganesh Mane, Pooja Shetty |
| Production Operator | Rahul Chavan, Sachin Patil (later limited to CHK-1) |
| Production Planner | Neha Kulkarni, Amit Sathe |
| Quality Engineer | Shreya Iyer, Kiran Bhat |
| Quality Inspector | Mahesh Shinde, Swati More |
| Quality Manager | Deepak Rao, Anjali Desai |
| Supplier Manager | Vivek Agarwal, Nisha Gupta |
| Viewer | Harish Menon, Leela Pillai |
| Auditor | Thomas Dsouza, Rina Sen |

**Data created:**
- **Plants:** CHK-1 Chakan Paint Works, TLG-1 Talegaon Resin Plant, VAP-1 Vapi Tinting Unit (Gujarat); 4 work centres; 5 machines.
- **Suppliers:** ASR (Apex Speciality Resins), TPG (Titan Pigments), GCP (Garden City Packaging, Karnataka).
- **Materials:** acrylic binder, TiO2, mineral turpentine, tint, 20 L pails.
- **Products:** EMU-WHT-B (bulk, LTR), EMU-WHT-20 (pail, PCS), ENM-RED-1.
- **Purchasing:** PO-2026-00001 to -00007; LOT-2026-00001 to -00009.
- **Inspections:** INS-2026-00001 to -00014.
- **Quality:** NCR-2026-00001 to -00008; CAPA-2026-00001 and -00002.
- **Production:** WO-2026-00001 (bulk 1,960 of 2,000 L) and WO-2026-00002 (97 of 100 pails); PB-2026-00001/-00002; serials EW20-0001 to -0097.
- **Dispatch:** DSP-2026-00001 and -00002; RET-2026-00001.
- **Recalls:** RCL-2026-00001 (closed), RCL-2026-00002 (open).
- **Other:** STR-2026-00001; instruments VIS-01, BAL-01, PH-01; AUD-2026-00001; one support request.

---

## 4. Role-based testing

- **Maker/checker** held everywhere, with two people per role:
  - suppliers (the submitter has no Approve);
  - BOM and recipe revisions (self-approval refused in the UI and with 422 from the API);
  - work orders, inspections, NCR closure and CAPA verification;
  - recall closure: Request → Send back → Request → Approve, with both sides notified and the note shown (C25);
  - audit closure.
- **Admins:** the owner's row shows only Edit roles and Facilities. The Owner, Plant Manager and Quality Manager boxes are disabled for an admin (C35).
- **Deactivation:** a deactivated auditor sees "This account is not active in any organisation. Ask your administrator to reactivate it." Reactivation restores login (C36).
- **Facility limits:** Operator 2, limited to CHK-1, sees only CHK-1 and its 7 lots, in the UI and through the API (C37).
- **Role × page sweep:** 45 pages for one user per role. Pages a role may not use say so. Three pages render over a refused call (D25).

---

## 5. Workflows and scenarios

| Workflow | Result |
|---|---|
| W1 Signup, verification, onboarding | Pass. Empty fields, an unverified sign-in and a wrong password are each refused with a message. A GSTIN with a wrong check character is refused (C20/C38). |
| W2–W3 Invitations | Pass. 22 users. An upper-case duplicate invitation is refused. |
| W4 Facilities, holidays | Pass with D1, D2, D3. "2026-12-25 is already a holiday here." State holidays apply per state. |
| W5 Master data, supplier approval | Pass with D4, D5, D6, D7, D8. An .exe upload is refused. The % unit is offered (C3). |
| W6 Purchase orders | Pass with D9. Only approved suppliers are offered. |
| W7 Receiving | Pass. AQL letters B/K/H for lots of 10.5, 2,000 and 500. |
| W8–W9 Templates, IQC | Pass with D11, D13. C5: counting plans on KG/LTR lots are refused at start. Send back needs a note and notifies the inspector. |
| W11 NCR / CAPA | Pass with D14, D15. Assign, containment, root cause, 5-why, fishbone, actions, verification and closure each need their text. C32 holds. |
| W12 Manufacturing setup | Pass with D8. C6 (BOM vs recipe products) and C7 (a work centre in another plant) hold. |
| W13 Plan, work orders | Pass. C8 holds. |
| W14 Shop floor | Pass with D17, D18. C9 (end-of-shift reason), C11 (rejects raise an NCR) and C15 hold. The grind gate must pass. |
| W15 Final inspection, serials | Pass. 97 serials. FQC per batch. |
| W16 Dispatch, returns | Pass with D19, D20. C21 (serials required), C22 (sample capped to quantity returned) and C23 (the return shows its NCR) hold. |
| W17 Trace, recall | Pass with D21. Forward and backward trace work. Dispatch from a recalled batch is refused. C25 and C26 hold. Closure waits for the linked CAPA. |
| W18 Calibration | Pass with D22, D23. An overdue instrument is not offered. A future calibration date is refused. A failure → out of service, NCR, in doubt; Reinspect starts INS-14 by itself (C31). |
| Stock transfer | Pass. C27 (received-on defaults to today) and C28 (trace of the transferred lot) hold. |
| Downtime, scheduling | Pass. C30: "A stop cannot end before it starts." C41: scheduling is axe-clean. |
| W19 Audits | Pass. Early completion is refused. A major finding raises NCR-8. The PDF is in words (C33). |
| W20 Analytics, reports | Pass with D24. C29 (per-unit rates), C33 (report words) and C34 (no OEE link for a viewer) hold. |
| W21 Users | Pass. C35, C36, C37. |
| W22 Settings, billing, support | Pass. A support request with required fields. Billing shows plan limits and usage. |
| Offline | Pass. C42: banner; "No connection, so this was not saved."; a retry when back online saves. |

---

## 6. Fix status

All issues were fixed except D18, which needs a product decision. Backend: `mvn verify` (with `RoundFourFixesIT`). Frontend: tsc, eslint and vitest.

| ID | Fix |
|---|---|
| D1 | A GSTIN and a state given together must agree. Refused with "The GSTIN is registered in Maharashtra, not in Gujarat." (facilities and suppliers). |
| D2 | A shift cannot end when it starts. Its break must be shorter than it. Shifts of a facility cannot overlap on any day of the week, including past midnight. |
| D3 | Day-month order (en-IN) on state holidays, the holiday calendar, scheduling and control charts. |
| D4 | Codes are unique ignoring case: suppliers, materials, products, customers, facilities, departments, production lines, work centres, machines, templates, checklists, defect categories and instruments, including an instrument's new ID on edit. |
| D5 | Record dialogs check a GSTIN (shape and check character) before sending it. The server message now starts "Not a valid GSTIN". |
| D6 | The submitter cannot reject their own supplier (server 422; the button is hidden), unless self-review is allowed. |
| D7 | Changing an approved supplier's name, legal name or GSTIN sends it back for a second approval, with a note. The edit dialog says so. |
| D8 | A product's material list refuses a unit of another kind. A BOM copied from the list is checked too. A failing release names the line and material ("Line 2 (TIO2): …"). |
| D9 | A material counted in pieces is ordered in whole numbers. |
| D10 | Dates read "26 Nov 2026" on the detail pages, panels, lot labels, plan headers, the serial page, the recall report, analytics ranges and billing. Notification and error messages use the same form. |
| D11 | A counting plan (AQL, %, every unit) cannot be published for a template whose own material or product is weighed or measured. |
| D12 | "…was closed with the disposition: Accept." |
| D13 | An AQL version cannot be published while a yes/no parameter has no expected answer. |
| D14 | An NCR names only a lot, supplier batch or production batch on record. |
| D15 | The NCR shows why each existing CAPA was linked, and the CAPA lists the NCRs linked to it, with the reason. |
| D17 | An end-of-shift pause hands the operation over: the next operator can resume it and becomes its operator. |
| D18 | **Not changed.** Checking output against issued material needs a yield rule per product. The variance at receipt still flags it. Left for a product decision. |
| D19 | Dispatch Date, Shipped on and Recall date default to today. |
| D20 | The inspection queue lists received returns waiting for inspection, with Inspect. New `GET /dispatch-reversals/awaiting-inspection`. |
| D21 | The recall and regulator PDFs show statuses in words and dates as "26 Nov 2026". |
| D22 | "1 record has used this instrument… Review it." / "2 records have…" |
| D23 | "Last calibrated" in the calibration report is the date of the latest calibration, whatever its result. |
| D24 | Valid definition-list markup in production analytics. |
| D25 | Customers shows the refusal instead of an empty list. NCRs fetch suppliers, and offer "Supplier issue", only to someone who can see suppliers. Quality checkpoints fetch templates only for someone who can manage checkpoints. |
