# Human testing report, round 3: ProductionOS

**Date:** 26 Nov 2026
**Build:** `dev` at `933ddb1` (after PR #180 and PR #181), local Docker Compose stack (Postgres, Mailpit, backend, frontend)
**How it was tested:** A real Chromium browser, driven through the UI the way people use it: clicking buttons, filling forms, reading on-screen messages and reading emails in Mailpit. Every user had their own browser profile and login, and each step was done by the role that does it in a real plant. Failed API calls and console errors were recorded throughout. The API was called directly only to check that the server enforces the same rules as the UI (permissions, tenant isolation, overdue instruments).
**What is new in this round:** A fresh organisation built from nothing, a foundry and machining business (Deccan Precision Castings, Nashik), with **two or three users per role**. That made it possible to test maker/checker, hand-overs, concurrent edits and "someone else must approve" with real people on both sides. The second-pass fixes (B21–B34) were re-checked inside the workflows.

---

## 1. Summary

| | |
|---|---|
| Workflows tested | 22 end-to-end workflows (W1–W22) plus stock transfer, material correction, downtime and scheduling |
| Scenarios checked | 230+ (listed in section 5) |
| Users | 26: an owner, then 2 per role, plus a 3rd Operator and a 3rd Inspector, plus one user with two roles (QE + Supplier Manager) |
| Role × page sweep | 45 pages × 13 users (one per role, plus the dual-role user) |
| Server permission checks | 30 direct API calls by roles that must be refused: all refused (see 4.3) |
| Tenant isolation | Passed: the other organisation gets 404 / "This customer does not exist." for every record |
| Accessibility | axe (WCAG 2.1 AA): 12 of 13 new pages clean. Skip link, dialog focus trap and focus return all work |
| Issues found | **5 medium, 28 low, 8 cosmetic** (C1–C42; C19 withdrawn) |
| Re-checks of earlier fixes | B22, B23, B24, B25, B29, B30, B33 and B34 all hold |

**Overall:** Every workflow could be completed by the right roles, and no data was lost or corrupted. Maker/checker holds everywhere it applies, with two users per role:
- BOM, recipe and routing revisions
- work orders
- rework orders
- suppliers
- material corrections, which need 2 approvals
- recall and audit closure

Admins cannot escalate themselves to Owner. The five medium issues are gaps that let bad data in or leave a user stuck:
- dispatching serial-tracked goods without serials (C21)
- a manual downtime that ends before it starts (C30)
- releasing an order whose required final inspection has no template (C17)
- AQL sampling a bulk lot by weight (C5)
- a reinspection after a failed calibration that the inspection queue never shows (C31)

---

## 2. Issues

### Medium

| ID | Issue | Where | How to reproduce | Evidence |
|---|---|---|---|---|
| **C21** | **Serial-tracked goods can be dispatched without serial numbers.** The serial check runs only when serials are given. With the box left empty, the dispatch is saved and the units leave untraced. Trace and recall then can't say which customer holds which unit. | `traceability/service/DispatchService.java` `ship()`: `if (serials.isEmpty()) return;` | Batch with serials → Record dispatch → quantity 1, serial box empty → Save | DSP-2026-00003 was saved for PB-2026-00001 × 1 without a serial, then reversed |
| **C30** | **A manual downtime that ends before it starts is saved as a 0-minute stop.** `DowntimeRecord.end()` moves an early end up to the start time, so the "A stop cannot end before it starts." check in `requireSensible` can never fire. | `production/entity/DowntimeRecord.java:84`, `DowntimeService.requireSensible` | Downtime → Record downtime → VMC-01, planned maintenance, start 30 Nov 06:00, end 30 Nov 05:00 → Save | Stored with `startedAt = endedAt = 2026-11-30T00:30Z` and `minutes 0.0` |
| **C17** | **An order can be released although its required final inspection has no template.** Configuration requires a final inspection before an order closes, but SAND-MIX had no FQC template. Release, the shop floor and receipt all went through. Only then did the order say "The batch is waiting for its final inspection." and the batch say "No published template fits this inspection." | `production/service/CompletionService.closeBlockers`; release checks | Recipe product with no FQC template → release → run → receive → order cannot close | WO-2026-00002. Workaround: QE2 built FQC-SAND, QM published it, Inspector 2 inspected, QM2 accepted |
| **C5** | **AQL sampling treats a bulk lot's weight as a count of units.** A 50,000 kg sand lot drew 500 samples × 2 parameters, so 1,000 readings. | AQL sample size from receipt quantity | IQC-SAND (AQL 2.5, level II) → receive 50000 KG silica → Inspect | INS-2026-00004 cancelled; worked around with template v2 (3 fixed samples) |
| **C31** | **After a failed calibration and a "Reinspect" decision, the inspection queue shows nothing.** The panel says "The new inspection could not start on its own. Start it from the inspection queue: the goods are held until then.", but the queue shows "Nothing is waiting.". After the reinspection passed and the batch was released, the panel still said the goods were held and didn't link the new inspection. It also listed Sahyadri as holding goods, though they had returned everything. | Calibration "Inspections in doubt" panel; inspection queue | BG-01 fails calibration → QM2 decides Reinspect on INS-13 → open Inspections | Reinspection INS-2026-00014 had to be started from the batch page |

### Low

| ID | Issue | Where / evidence |
|---|---|---|
| C2 | Cancelling an issued PO, or an inspection, asks for no reason (a confirmation only). | PO-2026-00003, INS-2026-00004 |
| C3 | Inspection templates have a fixed unit list (PCS, HB, HRC, CM, M, MM, GM, KG, °C, LTR, ML) with no % and no way to add one, so chemistry (C, Si, S, moisture) is unitless. Recipe parameters use free-text units. | Template editor |
| C7 | A routing accepts an operation at a work centre in **another plant** (WC-SMACH, Sinnar, in an NSK-1 routing). It is only caught at work-order release. | Routing HSG-200 rev 1 (sent back and fixed) |
| C9 | There is no "end of shift" or hand-over pause reason. An operator picked "Machine breakdown", which marked IF-01 broken down and blocked the next shift's resume ("Machine IF-01 is broken down.") until the PM set it back to Available. | Op 10 of WO-2026-00001 |
| C11 | Rejects and scrap can be reported with no defect category, so the automatic NCR has none (NCR-2026-00002, -00003). | Report quantities |
| C12 | The person who raised a rework order is offered "Approve", with a note field labelled "needed if you are approving your own rework order". Saving is then refused with "You raised this rework order, so someone else must approve it." | RW-2026-00001 |
| C13 | The rework "Reinspect" dialog offers the FQC template, which is then refused ("Final inspection needs a completed batch."). The IPQC pouring template is accepted, though it measures nothing about a weld repair. | RW-2026-00001. QE made IPQC-WELD |
| C14 | The Operator's operation page calls `GET /manufacturing/work-centers` and gets 403 (nothing on screen breaks). | Shop floor operation page |
| C15 | A failed process reading (sand temperature 35 °C against 20–32) needs no comment, notifies nobody and doesn't block completion. | WO-2026-00002 op 10 |
| C16 | Issue and Return stay on the order after finished goods are received, then fail with "Batch PB-2026-00001 is not in progress." | WO-2026-00001 |
| C18 | The final-inspection gate's Inspect is refused with "Final inspection needs a completed batch." while the order says "Completion waits for: Final inspection is pending.". Nothing says to receive finished goods first. | WO-2026-00001 |
| C20 | A customer's GSTIN is not validated at all ("BADGSTIN" saved). | Customer GPL |
| C22 | A fixed sample size larger than the quantity asks for samples that don't exist: 3 samples for a 2-unit return. Submitting with 2 fails with no visible message. | INS-2026-00011 |
| C23 | A return's page shows "NCR —" although its inspection raised NCR-2026-00005. | RET-2026-00001 |
| C24 | A serial's "Made" date is the UTC date (25 Nov) for goods received at 02:47 IST on 26 Nov. | /traceability/serials/HSG26-0005 |
| C25 | A recall closure request doesn't notify the people who approve it, and a send-back doesn't notify the requester. Audit closure does notify ("AUD-2026-00001 is ready for closure"). | RCL-2026-00001 |
| C26 | The final regulator PDF prints "Closed 2026-11-25T21:29:30.990584Z" (raw UTC). The recall could also close with hazard class "Not yet classified" and no regulator recorded. | RCL-2026-00001 final report |
| C28 | The timeline of a lot received on a date (no time) shows "26 Nov 2026, 5:30 am": midnight UTC shown in IST. | LOT-2026-00001-T1 |
| C29 | Production analytics adds PCS and KG together ("Reported 2057", "Good 2037"), so the rejection and scrap rates mix units. | /manufacturing/analytics |
| C32 | An NCR can't be linked to an existing CAPA. NCR-8 (from the audit) had the same root cause as the closed CAPA-1 but needed a duplicate, CAPA-2, to close. | NCR-2026-00008 |
| C34 | Analytics shows the Viewer an "OEE and output" link to production analytics, which then says "You do not have permission to do this." | /analytics as Viewer |
| C36 | A deactivated user signing in is told "This account does not belong to an active organisation.", though the organisation is active. | Auditor 2 |
| C37 | A user limited to one plant still sees the other plant in the facility filters and the facility list. Their data is correctly hidden. The users table doesn't show who is limited. | Operator 3 limited to NSK-1 |
| C38 | GSTIN checks only the format: no check digit and no state code. The test organisation's own GSTIN `27AAKCD4321M1Z9` has a wrong check digit (it should end in G) and was accepted. | Settings |
| C39 | A new password that is too weak is refused with no message on screen. | Settings → Change password |
| C40 | For a role without permission, the API runs body validation before the permission check. A malformed body returns 400 with field errors instead of 403 (e.g. Inspector `POST /instruments`). This is not a bypass: a valid body gets 403. | `@Valid @RequestBody` resolves before `@PreAuthorize` |
| C41 | axe: the scheduling capacity table scrolls sideways but can't be reached by keyboard (`scrollable-region-focusable`). | /manufacturing/scheduling |
| C42 | Offline, the banner says "Results you save are kept on this device and sent when it is back.", but saving a reschedule offline gives "Something went wrong. Please try again." Only inspection results are queued. | WO-2026-00003 |

### Cosmetic

| ID | Issue | Where |
|---|---|---|
| C1 | ISO dates still appear in places: holidays ("2026-12-25 – Christmas"), product revision "from 2026-11-26", PO order date, GRN received on, CAPA target and action due, planned dates on work orders and the schedule. | Several pages |
| C4 | Plurals: "1 samples", "1 lot(s) to inspect", "1 minutes", "1 attempts", "1 finding(s)". | Several pages |
| C6 | "New BOM" lists the process product SAND-MIX, then refuses it with a clear message ("takes a recipe, not a BOM"). | BOMs |
| C8 | When raising a work order from a plan, Planned end is pre-filled with the due date but Planned start is left empty ("Planned start is required"). | Plan → Raise work order |
| C10 | Production inspections label the product "Material" and the batch "Lot". Transfers say "Batch LOT-2026-00001 has only 11710 free." | Inspection header, stock transfer |
| C27 | When receiving a transfer, "Received on" has no default, while "Sent on" defaults to today. | Stock transfers |
| C33 | Raw enum values on screen and in PDFs: "Type SUPPLIER" (audit PDF), "PASS / ACCEPT" (report tables), "MAJOR non-conformance" (notification). | Audit PDF, reports, notifications |
| C35 | The Admin is offered Deactivate and the Owner checkbox on the Owner's row. Both are refused ("An organisation must keep at least one active owner.", "You cannot grant permissions you do not hold."). | /users as Admin |

C19 (missing messages when receiving finished goods) was withdrawn. The field-level "is required" messages are shown; my script had filtered them out.

### Re-checks of earlier fixes (all hold)

| Fix | Re-checked by | Result |
|---|---|---|
| B22 CAPA target cannot be today | QE2 raising CAPA-1 with today's date | "Choose a target date after today." |
| B23 transfer "Sent on" defaults to today | Production Manager sending STR-2026-00001 | Defaults to 2026-11-26 |
| B24 dialog keeps focus inside | Record downtime dialog, 40 Tabs + 15 Shift+Tabs | Focus never left. Esc returns focus to the button |
| B25 skip link | First Tab on /manufacturing/downtime | "Skip to content" → focus on `main#main` |
| B29 material correction needs two approvals | QE requests, QM approves (impact acknowledged), QM approves again, QM2 approves | Refused without acknowledgement; the same person can't approve twice; applied after 2 of 2; the T1 lot at the other plant changed too |
| B30 rework reinspection covers only what was reworked | RW-1: 2 to rework → 1 reworked, 1 scrapped | Reinspection INS-2026-00008 has quantity 1. "@ WC-MACH" steps parsed |
| B33 receive page and revision header | Recipe submit with no lines, then add a line | "Add at least one line before submitting." clears once a line is added |
| B34 resolving a recall customer with units still out | QE2 on Godavari (8 out), then Sahyadri (0 out) | Godavari: "8 units are still with this customer. Why is it resolved without them?" and an empty note is refused. Sahyadri: resolved with no question. After closure, "Edit regulator details" is gone |

### Observations (by design, or for product review)

- The supplier gets no email when a PO is issued, and the Supplier Manager is not notified of a supplier NCR.
- The operator a PM assigns an operation to gets no notification.
- CAPA action owners can include Viewers and Auditors. This is by design: owners complete their own action with view access.
- The QE can't add quality checkpoints; only the QM and Plant Manager can.
- Ordering more than a plan line is allowed but flagged ("60 PCS more than planned").
- The Production Manager handles dispatches and returns but can't open Recalls.
- A Yes/No parameter that needs evidence goes to the reviewer for judgement ("pending review") rather than failing automatically.
- An overdue instrument is not offered on an inspection, and the API refuses it ("DURO-01 is overdue for calibration, since 2026-11-20.").
- The material-correction impact and recall panels list Sahyadri as "holding" material after they returned everything.
- A serial's page lists every inspection of its batch, including cancelled ones and the rework reinspection of a different unit.
- Production analytics showed efficiency in the thousands of percent. This comes from the compressed test timings (operations started and finished within a minute), not from the app.

---

## 3. Test accounts and data

Password for every user: `correct-horse-battery`. Domain: `@deccancast.test`.

| Role | Users |
|---|---|
| Organisation Owner | Vikram Rao |
| Organisation Admin | Kavita Menon, Sunil Wagh |
| Plant Manager | Rajesh Kulkarni, Madhuri Patwardhan |
| Production Manager | Arjun Bhosale, Harish Pillai |
| Production Operator | Santosh Jadhav, Balu Shinde, Ravi Kale (later limited to NSK-1) |
| Production Planner | Divya Rane, Snehal Joshi |
| Quality Engineer | Nikhil Deshpande, Aditya Kulkarni |
| Quality Inspector | Priya Gaikwad, Pallavi Thorat, Mahesh Chavan |
| Quality Manager | Lakshmi Narayan, Gauri Sathe |
| Supplier Manager | Imran Sayyed, Asif Khan |
| Viewer | Tanvi Kapoor, Rohini Mane (changed to Auditor in W21) |
| Auditor | Joseph Fernandes, Shreyas Apte |
| Quality Engineer + Supplier Manager | Neeraj Bhatt |

**Data created:**
- **Plants and equipment:** NSK-1 Ambad Foundry and SNR-1 Sinnar Machining; 5 work centres; 6 machines.
- **Suppliers:** MSI (Mahalaxmi Steel and Iron) and NRM (Nashik Resin and Minerals).
- **Materials:** pig iron, FeSi, silica sand, resin.
- **Products:** HSG-200, HSG-200-R and SAND-MIX.
- **Purchasing and receiving:** PO-1 to PO-3; GRN-1 to GRN-4.
- **Inspection templates:** IQC-PIG, IQC-SAND v1/v2, IQC-FESI, IPQC-HSG, IPQC-WELD, FQC-HSG and FQC-SAND.
- **Inspections:** INS-2026-00001 to -00014.
- **Quality records:** NCR-2026-00001 to -00008; CAPA-2026-00001 and -00002.
- **Production:** PP-2026-00001; WO-2026-00001 (HSG-200 × 20, closed short at 16); WO-2026-00002 (SAND-MIX 2000 kg, closed short at 1985); WO-2026-00003 (approved); RW-2026-00001; PB-2026-00001 and -00002; serials HSG26-0001 to -0016.
- **Customers, dispatch and returns:** customers GPL and SVP; DSP-2026-00001 to -00003; RET-2026-00001 to -00003.
- **Traceability and audit:** RCL-2026-00001; STR-2026-00001; MC-2026-00001; instruments BG-01, DURO-01 and TC-01; AUD-2026-00001.

---

## 4. Role-based testing

### 4.1 Users per role

- **Owner:** signed up and verified their email.
- **First user per role:** the Owner invited 11 users, one per role.
- **Second wave:** the Admin invited 14 more: a second user per role, a third Operator and Inspector, and the dual-role QE + Supplier Manager.
- **Sign-up:** every user accepted from the Mailpit link and signed in.

### 4.2 What each role can see (45 pages × 13 users)

The UI sweep matched the role design. Notable results:
- Billing is owner-only.
- /users is for owner and admin.
- The audit log is for owner, admin, plant, QM and auditor.
- Audits and checklists are for owner, admin, QM and auditor (read for the rest, apart from the QE and inspector).
- Production analytics is refused for QE, Inspector, Viewer and Auditor (the Viewer is still shown a link to it, C34).
- The Supplier Manager sees nothing in manufacturing.
- The Operator only has the shop floor, issue and return, and the support page.

The dual-role user got the union of both roles. One finding from the sweep was a false positive: /roles works for the owner, but the sweep matched the word "permissions" in its table header.

### 4.3 Server enforcement (direct API calls)

All of the following were refused with 403. Where a malformed body first returned 400 (C40), a valid body was re-sent and got 403.

| Role | Calls refused |
|---|---|
| Operator | create supplier, approve work order, release work order, invite user, approve inspection |
| Viewer | create customer, close NCR, list users |
| Planner | release work order, approve work order, create dispatch |
| Inspector | approve inspection, register instrument |
| Auditor | raise NCR directly |
| QE | release work order |
| Admin | grant Owner role to self or another admin ("You cannot grant permissions you do not hold.") |

### 4.4 Maker/checker and multi-user scenarios

| Scenario | Result |
|---|---|
| Two Plant Managers edit the same facility | The second save gets "The record was changed by someone else. Reload and retry." |
| Supplier approval: Supplier Manager 1 submits | Supplier Manager 2 or the QE+SM user approves. The submitter gets no Approve |
| BOM, recipe and routing: PM 1 drafts | PM 2 approves. Self-approval refused in the UI and the API |
| Work order: Planner raises | PM 2 sends it back with a note; the Planner fixes and resubmits; PM 2 approves |
| Operation hand-over: Operator 1 starts and pauses | Operator 3 has no controls until the PM assigns them, then resumes and completes |
| Rework order: PM 1 raises | Refused for PM 1; PM 2 approves; Operators 2 and 3 do the steps |
| Inspections: Inspector submits | QM or QM2 approves or sends back. An Inspector can't approve |
| Material correction | Two different approvers, impact acknowledged |
| Recall closure: QE2 requests | Can't approve their own; QM2 sends back; QM approves |
| Audit closure | Blocked while NCR-8 is open; QM approves once it closes |
| Deactivate and reactivate | Auditor 2 is signed out, can't sign in, then signs in again after reactivation |

### 4.5 Tenant isolation

The previous organisation's owner tried this organisation's work order, NCR, recall, batch, inspection, audit and serial by ID. Every request got 404. Closing an NCR got 404. A dispatch naming this organisation's customer and batch got "This customer does not exist."

---

## 5. Workflows and scenarios

### W1–W3 Sign-up, verification and onboarding
Empty-field messages; sign-in refused until the email is verified; wrong-password message; onboarding wizard.

### W4 Facilities (Plant Manager)
- Created NSK-1 and SNR-1, including an overnight shift, holidays and a weekly off.
- A duplicate code is refused, and a negative break gets "Cannot be negative".
- The concurrent-edit check passed.
- The Viewer and Operator see no buttons.

### W5 Master data
- **Categories:** supplier categories belong to the Supplier Manager.
- **Suppliers:**
  - Supplier Manager 1 submits MSI; Supplier Manager 2 approves it.
  - NRM is rejected (a reason is required), resubmitted, and approved by the QE+SM user.
- **Materials:** 4 created; a missing unit gets "Unit is required".
- **Products:** 3 created; a spec with min > max is refused.

### W6 Purchasing (Supplier Managers)
- The order date defaults to today.
- Validation: quantity 0, and "The delivery date cannot be before the order date.".
- Only approved suppliers are offered.
- PO-1 and PO-2 were issued. PO-3 was issued and then cancelled (C2).

### W7 Goods receipt (Inspectors 1–3)
- GRN-1 to GRN-3 recorded.
- Over-receipt is refused: "Line 1: only 8000 remains to be received.".
- An expired lot is refused: "FR-OLD (RESIN) expired on 2026-11-20, before it was received.".
- GRN-4 voided with a reason, and the PO went back to 0 received.

### W8 Inspection templates (QE builds, QM publishes)
- QE1 built 5 templates; QM and QM2 published them. The QE has no Publish.
- QE2 made a new version, which QM published; the next inspection used v2 (C3).

### W9–W10 Incoming inspection
- **Pass path:** INS-1 passed and was accepted.
- **Fail path:** INS-2 failed on sulphur 0.08%, so QM2 rejected it and NCR-2026-00001 (critical) was raised automatically.
- **Send-back:** INS-3 was sent back (a note is required); the inspector was notified, corrected it and resubmitted.
- **Submit guard:** "MTC is incomplete." blocks an incomplete submit.
- **Cancellation:** INS-4 was cancelled because of C5.

### W11 NCR → CAPA → closure
1. The QM assigns the NCR to the QE, who records containment and root cause.
2. Verification without a CAPA is refused: "A critical NCR needs a closed CAPA.".
3. QE2 owns CAPA-1, with a 5-why analysis and a fishbone.
4. Actions go to the Supplier Manager, Inspector and Supplier Manager 2. Each owner sees only their own "Complete".
5. QM2 rules "Not effective", so the CAPA goes back to planning and gets a new action; then the QM rules "Effective" and closes it.
6. The NCR is closed with a note.

### W12 Manufacturing setup
- Settings: Hybrid mode.
- 5 work centres and 6 machines.
- BOM, recipe and two routings: PM 1 drafts, PM 2 approves.
- Checkpoints: IPQC at op 10 and a final FQC (C6, C7).

### W13 Planning → work orders
- PP-2026-00001; "The period cannot end before it starts.".
- WO-1, WO-2 and later WO-3 raised (C8; over-plan flag).
- Send-back and approval.
- Release reserves the oldest accepted lots, skipping the rejected lot, and shows recipe conditions, gates and planned cost.

### W14 Shop floor (Operators 1–3, PM, Inspector 3)
- **Issuing material:** operators can issue; the reserved lot and quantity are pre-filled. Over-issue is refused: "Line 1 needs 290 and 0 is already issued. Issuing more than an order needs is switched off in Configuration.".
- **Start and hand-over:** start requires a machine at the right work centre. Pause asks why; the hand-over needs the PM (C9).
- **Quality gate:** the gate blocks completion ("Operation 10 cannot be completed until its quality checks pass. Pouring temperature is pending."). Inspector 3 inspects from the gate, the QM approves, and the operation completes.
- **Setup time:** a setup time longer than the run time is refused.
- **Reporting:** rejects, rework and scrap are reported and raise NCRs (C11).
- **Rework RW-1:** raised, approved by the other PM, steps done by two operators, 1 reworked and 1 scrapped, reinspection, closed (C12, C13).
- **Next operation:** op 30 takes 17 (16 good + 1 reworked).
- **Sand order:** recipe parameter readings, 35 °C fails and 27 °C passes (C15).

### W15 Completion and final release
- **Receive:** finished goods need one serial per unit ("Give one serial number for each of the 16 units."); duplicates are refused; a short receipt and a material variance each need a reason.
- **FQC:** failed on bore 80.07; QM2 sent it back; the Inspector re-measured; it passed and the QM accepted. The batch was released (C18).
- **Returns after receipt:** refused (C16).
- **Close short:** with a reason; the production record PDF is correct.
- **Sand order:** blocked by C17, then closed short at 1985 kg.

### W16 Customers, dispatch and returns
- **Customers:** duplicate code refused (C20).
- **Dispatch rules:** a future date, too much quantity, a serial-count mismatch and an unknown or already-shipped serial are all refused (C21).
- **Reversal:** needs a reason and puts the stock back; it is not offered after shipping. A ship date before the dispatch date is refused.
- **RET-1:** 2 units in → inspected (C22) → QM fails and rejects them → NCR-5 (C23).
- **RET-2:** expected (not yet arrived), with an NCR raised up front. The received date is validated ("The return date must fall between the shipment and today."). Inspected, accepted, and the unit went back to stock.

### W17 Traceability and recall
- **Viewer searches:** lot, serial, batch and global search; forward and backward traces including reversals and returns (C24).
- **Recall report:** 8 + 4 units still out.
- **Recall RCL-1 (QE2):**
  - An early closure is refused and names the unresolved customers.
  - Contact attempts and acknowledgements recorded.
  - Resolving with units out needs a reason (B34).
  - Sahyadri returned 4 units.
  - Evidence uploaded.
  - Closure: send-back, re-request, QM approves (C25).
- **Final regulator PDF:** correct apart from C26.

### Stock transfer and material correction
- **STR-1:** 500 kg NSK → SNR. Same-plant, future-date and too-much transfers are refused. Arriving before it was sent is refused ("Goods cannot arrive before they were sent, on 2026-11-26."). LOT-2026-00001-T1 is traceable (C27, C28).
- **MC-1:** heat number HEAT-N1 → HEAT-N1A, with two approvals, reaching the T1 lot (B29).

### Downtime and scheduling
- The shop-floor breakdown appears in the downtime list. A manual entry with empty fields shows the required-field messages (C30).
- A planned maintenance stop on IF-02 cut that day's WC-MELT capacity from 30 h to 22 h.
- WO-3's load is spread over working days, with Sundays at 0. A reschedule needs "Why it moves" and re-spreads the load (C29, C41).

### W18 Calibration
- The QE registers instruments; the Inspector can't.
- The overdue DURO-01 is hidden from inspections and refused by the API.
- BG-01 failed calibration → it went out of service, NCR-7 was raised and INS-13 was put in doubt.
- QM2 chose Reinspect → the batch went on hold → INS-14 passed → the batch was released (C31).

### W19 Audits
1. The Auditor builds a checklist and plans a supplier audit of MSI, with Auditor 2 as lead.
2. Early completion is refused ("4 checklist question(s) have no answer.").
3. A major finding raised NCR-8 automatically.
4. Closure is blocked while NCR-8 is open. NCR-8 went through containment and root cause, and needed CAPA-2 (C32).
5. The QM approves closure. The PDF is correct apart from C33.

### W20 Analytics and reports
- Viewer: KPIs and control charts (C34).
- Inspection, NCR, batch traceability, work order and downtime reports all download as valid PDF and XLSX for the Viewer; the Planner gets the work order and production quality reports.

### W21 User administration
- The owner is protected (C35).
- No escalation to Owner.
- Deactivate and reactivate (C36).
- A role change from Viewer to Auditor takes effect.
- Facility limits hide the other plant's data (C37).
- Invitations:
  - With no role: "Choose at least one role."
  - To an existing member: "This user is already a member of the organisation."
  - A duplicate pending invite: "This address already has a pending invitation."
- Revoke: the link then says "This invitation is not valid or has expired. Please ask for a new one."

### W22 Settings, billing, account and support
- GSTIN (C38). Billing is owner-only.
- A downgrade is refused: "The Growth plan allows 15 users. Remove some users or pending invitations first.". No payment was made in this round.
- Password: wrong current password and an unchanged password are refused (C39); a valid change re-signs the user in.
- Support requests: each user sees their own, the Admin sees all.
- Notifications: mark all read.

### Accessibility and offline
- axe was run on 13 pages: dashboard, a work order, a shop-floor operation, a recall, an inspection, scheduling, downtime, a trace, calibration, users, billing, an audit and reports. Only C41 was found.
- Keyboard: the skip link, focus trap and focus return all work.
- Offline: the banner appears and goes away; a non-inspection save fails with a generic message (C42).

### Fix status (branch `fix/round-3-bugs`)

Backend `mvn verify`: 207 unit tests and 773 integration tests pass. Frontend: tsc, lint and vitest (222) pass. New regression tests: `RoundThreeFixesIT`, `GstinValidatorTest`, `EnumLabelsTest`, `gstin.test.ts`. "Re-tested" means checked again in the browser on the rebuilt stack.

| Bug | Status | Fix |
|---|---|---|
| C21 | ✅ Fixed, tested | A batch whose units carry serial numbers can't be dispatched without them. `RoundThreeFixesIT.aBatchTrackedBySerialNumberShipsByThem`. |
| C30 | ✅ Fixed | A stop can't end before it starts, on create, edit or end. |
| C17 | ✅ Fixed, tested | Release is refused, and the order lists the problem, while final inspection is required and no published FQC template fits the product. |
| C5 | ✅ Fixed, tested | AQL, percentage and every-unit plans refuse a lot weighed or measured (KG, LTR…) and ask for a fixed sample size. |
| C22 | ✅ Fixed, tested | A fixed sample size is capped at a counted lot's quantity. |
| C31 | ✅ Fixed | A reinspection now starts for accepted returned goods too. The panel links the held goods when it couldn't start. Customers who returned everything are no longer listed. |
| C2 | ✅ Fixed, tested | Cancelling an inspection, or an issued PO, asks for a reason, which is audited. |
| C3 | ✅ Fixed | Shared unit `%` (Percent) added (V76). |
| C7 | ✅ Fixed | A routing's operations must all be at work centres in one plant: checked on add, edit and submit. |
| C9 | ✅ Fixed | New pause reason "End of shift / hand-over" (V77): planned downtime, and the machine stays available. |
| C11 | ✅ Fixed | Rejects and scrap that raise an NCR need a defect category, when the organisation has defect categories. |
| C12 | ✅ Fixed | Approve is hidden from whoever raised the rework order when approval is separated. |
| C13 | ✅ Fixed | Reinspect offers FQC templates only once the batch is completed. |
| C14 | ✅ Fixed, re-tested | People who carry out production can list work centres; the operator's pages make no failing calls. |
| C15 | ✅ Fixed | A reading outside its limits needs a comment and notifies supervisors and NCR raisers (new notification type). |
| C16 | ✅ Fixed | Issue, return and reserve are hidden once finished goods are received. |
| C18 | ✅ Fixed | The message now says "Receive the finished goods on the work order first." |
| C20, C38 | ✅ Fixed, tested | GSTIN check character validated on customers, suppliers, facilities and the organisation, on screen and on the server. The test organisation's 27AAKCD4321M1Z9 must be corrected to 27AAKCD4321M1ZG. |
| C23 | ✅ Fixed, re-tested | A return shows the NCR its inspection raised when there was no complaint NCR. |
| C24 | ✅ Fixed, re-tested | Serial "Made" dates in local time. |
| C25 | ✅ Fixed | Recall closure requests notify RECALL_CLOSE holders; a send-back notifies the requester. |
| C26 | ✅ Fixed (date) | The regulator PDF prints "Closed" in the organisation's time zone. Requiring a hazard class before closing is a policy decision and was not changed. |
| C28 | ✅ Fixed | Date-only timeline entries (lot received) show the day without a time. |
| C29 | ✅ Fixed, re-tested | Analytics lists quantities per unit; with mixed units, each rate is the mean of the units' own rates. |
| C32 | ✅ Fixed | "Link existing CAPA" on an NCR (V78), with a note. Linked CAPAs count towards closing the NCR. |
| C34 | ✅ Fixed, re-tested | The "OEE and output" link needs PRODUCTION_ANALYTICS_VIEW. |
| C35 | ✅ Fixed, re-tested | Roles with permissions you don't hold can't be ticked, and members who outrank you have no Deactivate. |
| C36 | ✅ Fixed | "This account is not active in any organisation. Ask your administrator to reactivate it." |
| C37 | ✅ Fixed, re-tested | The facility list and pickers show only a limited member's plants. The users table has a Facilities column. |
| C39 | ➖ Not a bug | "Use at least 10 characters" does show under the field. The test script's log filter hid it. |
| C40 | ✅ Fixed, re-tested | `@PreAuthorize` is checked before the body is read, so a malformed body without permission gets 403. |
| C41 | ✅ Fixed | The capacity table's scroll region is focusable and labelled. |
| C42 | ✅ Fixed | The banner says only inspection results are kept offline; other saves offline say "No connection, so this was not saved." |
| C1 | ✅ Fixed, re-tested | Date-only values are shown as "26 Nov 2026" across lists and detail pages. |
| C4 | ✅ Fixed | Singular forms for samples, lots, minutes, attempts and findings. |
| C6 | ✅ Fixed | New BOM / New recipe offer only products made that way. |
| C8 | ✅ Fixed | Planned start defaults to today (or the due date if that is earlier). |
| C10 | ✅ Fixed | Production inspections say Product / Batch; a lot's message says "Lot". |
| C27 | ✅ Fixed | "Received on" defaults to today. |
| C33 | ✅ Fixed | Report status/result/type columns, the audit PDF and the NCR notification show words ("Supplier", "Pass", "Major"). |
