# Human testing report, round 5: ProductionOS

**Date:** 26 Nov 2026
**Build:** `dev` at `1b70ef2` (after PR #185, the round 4 fixes), on the local Docker Compose stack (Postgres, Mailpit, backend, frontend). The fixes were then re-tested on images built from branch `fix/round-5-bugs`.
**How it was tested:** In a real Chromium browser, through the UI, the way people use it. Each user had their own browser profile and login, and each step was done by the role that does it in a real plant. The API was called directly only to check that the server enforces the same rules as the UI, or to read back what was stored.
**What is new in this round:**
- **A fresh organisation in a new industry:** Shivneri Auto Components, a maker of machined and assembled auto parts. Its plants are Waluj and Shendra in Maharashtra, and Hosur in Tamil Nadu.
- **Counted goods throughout:** castings, bearings and hub assemblies counted in pieces, next to steel bar in KG and coolant and grease in bulk.
- **Yes/no checks as the critical parameter:** porosity on castings, and noise and spin on bearings.
- **Pages opened by URL:** pages a role cannot see in the menu were opened directly, to check what they show.
- **Round 4 fixes rechecked:** every D-series fix was re-checked inside the workflows.

---

## 1. Summary

| | |
|---|---|
| Workflows tested | Signup and invitations, facilities, master data and supplier approval, purchasing, receiving, templates, IQC, NCR and CAPA lifecycles, manufacturing set-up, work orders, shop floor, final inspection, dispatch and returns, traceability, recall, audits, stock transfer and downtime |
| Users | 23: an owner, then 2 per role (11 roles) |
| Pages opened by URL | 45 pages, as an Operator, a Viewer and a Supplier Manager |
| Accessibility | axe (WCAG 2.1 AA): over 40 pages. Two report pages failed (E15); every other page was clean. |
| Issues found | **1 high, 1 medium, 9 low, 4 cosmetic** (E1–E15) |
| Round 4 fixes re-checked | D1–D17 and D19–D25 all hold. D18 was left for a product decision and is unchanged. |

**Overall:** Every workflow could be completed by the right roles. Maker/checker held on every approval.

The one high issue (E6) gave wrong quality decisions, not just a wrong display:
- A yes/no check whose expected answer had been typed as "Yes" failed every good part.
- A sound lot of 300 castings went on hold.
- A critical NCR was raised against it.

The medium issue (E5): the round 4 whole-number rule for counted items applied only to purchase orders. A receipt of 10.5 castings was accepted, as were fractional quantities in several other places.

All 15 issues are fixed and re-tested in the browser.

---

## 2. Issues

### High

| ID | Issue | Where | How to reproduce | Evidence |
|---|---|---|---|---|
| **E6** | **A yes/no check expecting "Yes" fails every "Yes" answer.** The template editor took the expected answer as free text. The quality engineer typed "Yes". The evaluation read the answer with `Boolean.parseBoolean`, and anything except "true" became false. So every good part failed and every bad part would have passed. | Template editor, `EvaluationEngine` | Yes/no parameter, expected "Yes" → publish → inspect, answering Yes | INS-2026-00001: 50 castings, all hardness in range, all porosity "Yes" → POROS failed 50 times; lot auto-held; critical NCR-2026-00002 raised for a good lot |

### Medium

| ID | Issue | Where | How to reproduce | Evidence |
|---|---|---|---|---|
| **E5** | **Counted items accepted fractions everywhere except on the purchase order.** A goods receipt took 10.5 castings. A probe found the same gap in an NCR's rejected quantity (2.5), a batch's planned quantity (2.5), consumption (0.5) and completion (2.5). | Receipts, NCRs, production batches | Receive 10.5 of a PCS material | GRN for JSB-C119; the CAST-HUB lot then showed "278.5 free" |

### Low

| ID | Issue | Where / evidence |
|---|---|---|
| E2 | The Maharashtra city list used old names ("Aurangabad", "Ahmednagar"). Jalna, Waluj and Shendra were missing. | Facility, supplier and customer dialogs |
| E3 | Submitting a supplier for approval, or sending it back after a rename (D7), told no one. The second supplier manager had no notifications. | Supplier page |
| E4 | `/purchase-orders/new`, opened by URL by someone who cannot manage purchasing, showed the full order form over a refused supplier list. | Planner, Operator, Viewer, Supplier Manager |
| E7 | In a long dialog, the server's refusal appeared at the top, out of sight of Save. | NCR dialog (D14 check); any long dialog |
| E9 | A customer return's "Date received or expected" had no default, though it is required. The sweep found the same in return receipt, complaint, calibration and recall contact dates. | Dispatch → Record return, and others |
| E10 | An audit finding recorded against a checklist question didn't take the question's clause. The finding on question 3 (clause 8.7) had no clause. | AUD-2026-00001, finding 2 |
| E11 | A major nonconformity could be recorded against a question answered Conforming. It raised NCR-2026-00008. | AUD-2026-00001, finding 1 |
| E14 | Pages opened by URL without permission:<br>- A mistyped record link (`/ncrs/new`) read "Request validation failed".<br>- Roles showed an empty table with no message.<br>- Downtime showed its filters and two refusals. | Operator, Viewer, Supplier Manager |
| E15 | The audit and recall report tables had white headings on light grey (contrast 1.17), so they were unreadable. axe: `color-contrast`. | `/audits/{id}/report`, `/recalls/{id}/report` |

### Cosmetic

| ID | Issue | Where |
|---|---|---|
| E1 | After a server refusal, entering another bad value kept the old server message above the new field error. | All forms |
| E8 | Raw ISO dates in a few places:<br>- the report period ("2026-08-29 to 2026-11-26");<br>- report table cells, such as calibration dates;<br>- the shop floor's "Due 2026-12-05";<br>- about 14 notification and error messages (CAPA due, audit scheduled, plan period, overdue calibration, scheduling, recall, invoices, export ready). | Reports, shop floor, notifications |
| E12 | The audit and recall reports showed "Status CLOSED" (a raw value). | Report pages |
| E13 | More raw ISO dates: the dashboard's Recent activity ("Inspection, 2026-11-26"), a recall's Contact history, and the BOM's "In total, on". | Dashboard, recall, BOM |

### Observations (not bugs)

- Once a recall is closed, its batch can be dispatched again. Holds apply only while a recall is open, by design. A draft dispatch made to check this (DSP-2026-00002) was reversed.
- An audit can be planned for a past date. It is then listed as overdue, which suits recording an audit already held.
- Downtime "Start time" has no default, though the date defaults to today.
- Saved records keep the city they were given. Supplier JSB still shows "Ahmednagar" after E2.
- Plant managers cannot send stock between plants: that needs TRACEABILITY_MANAGE, which the role does not hold.
- In the D20 re-check, a return with only one fitting template didn't have it preselected. This could not be reproduced: the API suggests the single template, and the dialog uses that suggestion.

---

## 3. Test accounts and data

Password for every user: `correct-horse-battery`. Domain: `@shivneri.test`.

| Role | Users |
|---|---|
| Organisation Owner | Vikas Deshmukh |
| Organisation Admin | Irfan Shaikh, Meghna Bose |
| Plant Manager | Prakash Jadhav, Lavanya Raman |
| Production Manager | Sanjay Kale, Divya Murthy |
| Production Operator | Ramesh Pawar, Murugan Selvam |
| Production Planner | Ashwini Gore, Karthik Iyer |
| Quality Engineer | Nandini Rao, Rohan Mehta |
| Quality Inspector | Sunita Waghmare, Arun Kumar |
| Quality Manager | Suresh Naidu, Priyanka Kulkarni |
| Supplier Manager | Hemant Joshi, Farida Ansari |
| Viewer | Gopal Krishnan, Smita Patankar |
| Auditor | Francis Pinto, Aparna Ghosh |

**Data created:**
- **Plants:** WLJ-1 Waluj Machining, SND-1 Shendra Forge, HSR-1 Hosur Assembly (Tamil Nadu).
- **Suppliers:** JSB (Jalna Steel and Bars), BBC (Bengaluru Bearings Co, Karnataka), TCP (Tamil Coolants and Petro), MSP (Marathwada Steel Processors).
- **Materials:** EN8 bar (KG), SG iron hub castings (PCS), 6205 bearings (PCS), grease (KG), coolant.
- **Products:** HUB-MC (machined hub), HUB-ASSY (front wheel hub assembly), BRKT-01.
- **Purchasing:** PO-2026-00001 to -00004; GRN-2026-00001 to -00006; LOT-2026-00001 to -00008.
- **Inspections:** INS-2026-00001 to -00009. INS-9 is the E6 re-test: all "Yes" → pass.
- **Quality:** NCR-2026-00001 to -00010; CAPA-2026-00001 (closed, effective).
- **Production:** WO-2026-00001; PB-2026-00001 and -00002.
- **Dispatch:** DSP-2026-00001; DSP-2026-00002 (reversed, never shipped); RET-2026-00001.
- **Recall:** RCL-2026-00001 (closed).
- **Audits:** AUD-2026-00001 (closed); AUD-2026-00002 (in progress, the E10/E11 re-test).
- **Other:** STR-2026-00001 (received as LOT-2026-00001-T1); downtime on CNC-01.

---

## 4. Role-based testing

- **Maker/checker** held throughout:
  - Suppliers: the submitter has no Approve or Reject; the second supplier manager approves.
  - Inspections: QM approves.
  - CAPA: only the QM verifies effectiveness.
  - NCRs: the engineer requests verification; a QM closes.
  - Recall closure: requested by one QM, approved only by the other.
  - Audit closure: requested by the lead auditor, approved by a QM.
- **CAPA actions:** each owner was notified and saw Complete only on their own action.
- **Pages opened by URL:** 45 pages each as an Operator, a Viewer and a Supplier Manager. Pages a role may not use say so with a single message, apart from those in E4 and E14.

---

## 5. Workflows and scenarios

| Workflow | Result |
|---|---|
| W1–W3 Signup, invitations | Pass. A bad GSTIN and a wrong check character are refused. 22 invitations accepted. |
| W4 Facilities | Pass with E1, E2. D1, D2, D3, D4 and D10 hold. Pongal applies only to Hosur. |
| W5 Master data, suppliers | Pass with E3. D4, D6, D7 and D8 hold. An .exe upload is refused. |
| W6 Purchase orders | Pass with E4. D9 holds (100.5 bearings refused). |
| W7 Receiving | Pass with E5. Over-receipt refused; expiry from shelf life. |
| W8–W9 Templates, IQC | Pass with E6. D11 and D13 hold. A reviewer Fail + Reject on bearings raised NCR-1 (major, supplier BBC). |
| W11 NCR and CAPA | Pass. CAPA-1 went through analysis, two actions, owner completion, submission, the QM's effectiveness check and closure. NCR-1 went through assign, containment, root cause, verification and closure. D15 holds. |
| W12–W14 Manufacturing, shop floor | Pass. A work order needs a published FQC template. D17 holds: the next operator can resume after an end-of-shift pause. |
| W15–W16 Final inspection, dispatch, returns | Pass with E9. D19 and D20 hold. The return raised NCR-6. |
| W17 Trace and recall | Pass. Search works by batch, lot, supplier batch, GRN, material and product. Backward and forward traces, dispatches, returns and the timeline all work, and a viewer can trace. Early closure explains the unresolved customer. Resolving with goods still out needs a note. |
| W18 Calibration | Pass. D22 and D23 hold. |
| W19 Audits | Pass with E10, E11, E12, E15. Completion needs a finding for every nonconforming answer. Nonconformities raise NCRs at once. An open NCR blocks closure. |
| Stock transfer | Pass. Sending to the same plant, more than is free, or a negative quantity is refused. 500 kg of EN8 was sent from Waluj to Hosur and received as LOT-2026-00001-T1. |
| Downtime | Pass with E14. A stop ending before it starts is refused, and so is a future start. End now and the summary totals work. |
| Reports, analytics | Pass with E8, E13. D21 and D24 hold. |

---

## 6. Fix status

All 15 issues are fixed on branch `fix/round-5-bugs`, and each was re-tested in the browser on rebuilt images.

Checks:
- **Backend:** `mvn verify`: 209 unit tests and 786 integration tests, all green. This includes the new `RoundFiveFixesIT` and new cases in `AuditManagementIT`, `EvaluationEngineTest` and `GlobalExceptionHandlerTest`.
- **Frontend:** tsc and eslint are clean, and vitest has 225 tests passing.

| ID | Fix | Re-tested |
|---|---|---|
| E6 | The template editor offers a choice for the expected answer: Reviewer decides, Yes or No. The server stores it as true or false. It still reads yes/no/y/n from old snapshots, and refuses any other word. Migration V79 converts stored answers (POROS "Yes" → "true"). Requirements and results show "Yes"/"No". | INS-2026-00009: all "Yes" → pass, no hold, no NCR. The template and report read "Yes". |
| E5 | Counted items need whole numbers everywhere a quantity is entered: receipts and corrections, lot transfers, NCR rejected quantity, batch planned/produced, consumption and its return, work-order quantity, reserve and issue, plan lines, dispatch, customer returns and batch transfers. | 10.5 castings refused: "CAST-HUB is counted in PCS, so give a whole number, not 10.5."; 10 accepted. |
| E1 | Every form clears the last server message on each submit, even when browser validation stops it: record dialogs, receiving, purchase orders, templates, roles, users, settings, organisation and the sign-in pages. | -1 after a refused 10.5 shows only "Cannot be negative". |
| E2 | Maharashtra lists Chhatrapati Sambhajinagar and Ahilyanagar. Added: Jalna, Beed, Parbhani, Dharashiv, Yavatmal, Gondia, Baramati, Waluj and Shendra. | Offered; the old names are gone. |
| E3 | Submitting a supplier, or sending it back after a rename, notifies everyone who can approve suppliers ("… needs approval"). | Supplier 2 and the admin notified; the submitter is not. |
| E4 | The purchase order form needs PURCHASE_MANAGE, for new and edit. | The operator sees "You do not have permission to do this." |
| E7 | A form's error message scrolls into view when it appears. | In a 4-line Send stock dialog at 640 px, the message is in view. |
| E8 | Report period and date cells, the PDF report cells, the shop floor's Due date, and 14 backend messages read "26 Nov 2026". XLSX keeps ISO dates for spreadsheets. | No ISO dates in the NCR, inspection and calibration reports, or on the shop floor. |
| E9 | Today is the default for the return date, return receipt, complaint received-on, calibration date, recall contact attempt and acknowledgement. | Return and complaint dialogs open with today. |
| E10 | A finding against a question takes the question's clause unless another is given. The field reads "Clause, if not the question's own". | Findings took 7.1.5 and 8.7. |
| E11 | A nonconformity cannot be recorded against a question answered Conforming. A question with a nonconformity cannot be re-answered Conforming. An observation is still allowed. | Both refused with their messages; the observation was accepted. |
| E12 | Report statuses in words. | "Status closed". |
| E13 | The dashboard's activity, a recall's contact history and the BOM total date read "26 Nov 2026". | Dashboard: "Inspection, 26 Nov 2026". |
| E14 | A path ID that is not an ID returns 404, and the page shows "This record does not exist." Roles shows the refusal. Downtime shows one message to someone who cannot see manufacturing. | All three shown as described. |
| E15 | The report tables use the shared blue heading band. The sticky scheduling heading uses it too. | axe: both reports clean. |

Also changed: moving a lot from the wrong plant now reads "Lot LOT-… is not at the plant it is sent from." (it used to call a lot a batch). On the inspection queue, the Inspect button for returned goods needs INSPECTION_CREATE.

D18, from round 4, is still unchanged: checking output against issued material needs a yield rule per product.
