# Human testing report: ProductionOS

**Date:** 25 Nov 2026
**Build:** `dev` at `49f94b0`, local Docker Compose stack (Postgres, Mailpit, backend, frontend), version 0.1.0-SNAPSHOT
**How it was tested:** A real Chromium browser, driven through the UI the way a person uses it: clicking buttons, filling forms, reading on-screen messages and reading emails in Mailpit. Each role used its own browser profile and login. Every step also recorded browser console errors and failed API calls. Screenshots of the key screens were checked by eye. The API was called directly only to check that the server enforces the same rules as the UI (permissions, self-approval and tenant isolation).
**Screen sizes:** 1440×900 desktop, plus spot checks at 390×844 (phone).

---

## 1. Summary

| | |
|---|---|
| Workflows tested | 33 (W1–W22 first pass, W23–W33 second pass) |
| Scenarios checked | 186 (in the tables below) |
| Roles tested with their own login | 12 (every system role) |
| Server permission checks (role × action) | 115, 0 mismatches |
| Bugs found | First pass: 1 high, 6 medium, 13 low or cosmetic (B1–B20, fixed). Second pass: 5 medium, 9 low (B21–B34, fixed on `fix/second-pass-bugs`) |
| Tenant isolation | Passed: another organisation sees "This record does not exist." for every record |

**Overall:** The core quality loop works end to end, from goods-in and inspection through NCR, CAPA and closure, and so do traceability, recall, calibration, audits, reports and user administration. Role permissions are enforced on the server as well as hidden in the UI, and maker/checker (no one approves their own work) holds everywhere it applies. One high-severity bug traps a work order permanently (see B1). The rest are mostly error messages that are missing or misleading.

---

## 2. Bugs and issues

### Fix status (branch `fix/human-test-bugs`)

| Bug | Status | Fix |
|---|---|---|
| B1 | ✅ Fixed, re-tested | A final (FQC) inspection on the order's batch now decides the final-release gate, wherever it was started. WO-2026-00001 then closed "partially completed". Regression test: `ProductionCompletionIT.aFinalInspectionStartedFromTheBatchStillReleasesTheOrder` (fails without the fix). |
| B2 | ✅ Fixed, re-tested | A wrong current password now returns 422 "Your current password is incorrect." and you stay signed in. Reusing the current password is refused. |
| B3 | ✅ Fixed, re-tested | The header shows the reviewer's final result, e.g. INS-2026-00001 "completed · pass · accept". |
| B4 | ✅ Fixed | The "doesn't add up" message is now shown on the form ("Good, rejected and scrap make 96, not 100."). |
| B5 | ✅ Fixed, re-tested | Billing shows "You do not have permission to do this." without SUBSCRIPTION_MANAGE, and makes no API calls. |
| B6 | ✅ Fixed, re-tested | The PDF counts the batch's serials when it is made: "95 serial numbers recorded". |
| B7 | ➖ Left as designed | Reporting and completing while paused is deliberate: both the server and the UI allow it, so output made before the stop can be recorded. |
| B8 | ✅ Fixed, re-tested | Billing events have labels and are shown only to people who manage billing. |
| B9 | ✅ Fixed, re-tested | A return inspection shows only on the serials that came back (DS-0042 no longer lists INS-2026-00006). |
| B10 | ✅ Fixed | "has only 95 available." |
| B11 | ✅ Fixed, re-tested | Each mistake gets its own message: "Enter a number", "Cannot be negative", "Use at most 6 decimal places". |
| B12 | ✅ Fixed | `ValidationMessages.properties` replaces the default validation wording everywhere, e.g. "Choose a date after today.", "Enter a number above zero." |
| B13 | ✅ Fixed, re-tested | One date style everywhere: "25 Nov 2026, 10:42 pm" (`frontend/src/lib/dates.ts`). |
| B14 | ✅ Fixed, re-tested | The submitter no longer gets Approve and sees "You submitted this revision, so someone else must approve it." (also for work orders). |
| B15 | ✅ Fixed, re-tested | Out-of-service or retired instruments show no "in date" badge. |
| B16 | ✅ Fixed, re-tested | Evidence on other people's actions shows an explanation instead of "No evidence yet.", and the page no longer makes refused calls. |
| B17 | ✅ Fixed, re-tested | No logo now returns 204, not 404. |
| B18 | ✅ Fixed, re-tested | Work centres are looked up only for the rework dialog; the Operator's WO page makes no refused calls. |
| B19 | ✅ Fixed | Gate Inspect opens the new inspection. The reserved or only lot is pre-selected when issuing. The duplicate-serial error names the serials. Choosing a plan asks for confirmation. "1 row" and "1 facility". |
| B20 | ✅ Fixed | Locked dropdowns are greyed like the number fields. |

Tests: backend `mvn verify` passes 962 tests with 0 failures. Frontend typecheck and lint are clean, and 216 unit tests pass.

### High

| ID | Issue | Where | How to reproduce | Evidence |
|---|---|---|---|---|
| **B1** | **A work order can never be closed if its final inspection is done from the production batch page.** The batch is released, but the work order's "Final release inspection" gate stays *pending*. Inspecting from the gate is then refused with "Final inspection needs a completed batch." because the batch is already released. The owner has no override. The PDF production record also prints "Final release inspection: pending". | `backend/.../production/service/QualityGateService.java` (gates only count inspections started via `POST /work-orders/{id}/quality-gates/{gate}/inspections`), batch page "Inspect" button | WO with a final-release checkpoint → finish ops → receive finished goods → open the **production batch** → Inspect → FQC template → submit → second person approves + Accept → open the WO: gate pending; click gate Inspect → 422 | WO-2026-00001 is stuck "in progress"; batch PB-2026-00001 is released |

Possible fixes: link a final inspection on a work-order batch to that order's final-release gate; or hide "Inspect" on the batch page for batches that came from a work order; or let the gate accept the batch's passed final inspection.

### Medium

| ID | Issue | Where | How to reproduce |
|---|---|---|---|
| **B2** | **A wrong "current password" signs the user out.** The backend answers 401 invalid-credentials, and the frontend treats every 401 as an expired session and redirects to /login. The user expects a "current password is incorrect" message. | `AuthService.changePassword` (backend/.../identity/service/AuthService.java:382) + `frontend/src/lib/api.ts:46` | My account → Current password = anything wrong → Change password → lands on /login, session gone |
| **B3** | **The inspection header keeps saying "pending review" after the reviewer decides.** When a yes/no parameter is "Reviewer decides", the header shows `evaluatedResult` (PENDING_REVIEW) instead of `finalResult` (PASS/FAIL), even after approval and disposition. | `frontend/src/app/(app)/inspections/[id]/page.tsx:584` | Template with a yes/no parameter → inspect → submit → approve with Result = Pass → header: "completed · pending review · accept" (INS-2026-00001, -00003) |
| **B4** | **Shop-floor quantity report that doesn't add up shows only "Something went wrong. Please try again."** The page builds a useful message ("Good, rejected and scrap make 96, not 100.") and rejects with a plain `Error`, but the form shows only `ApiError` messages. | `frontend/src/components/forms/apply-server-errors.ts:9`, `shop-floor/[id]/page.tsx:386` | Operation → Report quantities → Processed 100, Good 90, Rejected 4, Scrap 2 → Save |
| **B5** | **Billing page open to every role, with a Cancel button that fails silently.** Any user can open /settings/billing by URL (it is hidden from their menu). Non-owners see "Cancel subscription"; confirming it closes the dialog with no message (server 403). The page also fires 5 failing calls (plans, invoices, payments, payment-gateway, credit-notes) and shows empty sections. Other pages show "You do not have permission". | `/settings/billing` | Sign in as Viewer → go to /settings/billing → Cancel subscription → Confirm |
| **B6** | **The production record PDF says "0 serial numbers recorded"** for a batch with 95 serials (serials added after finished-goods receipt aren't counted). | WO "Production record (PDF)" | Receive finished goods, then add serials on the batch, then download the record |
| **B7** | **The operator can report quantities and complete an operation while it is paused for a machine breakdown.** Resume is correctly refused ("Machine VMC-01 is broken down."), but Report quantities and Complete stay available. | Shop floor operation page | Start op → Pause "Machine breakdown" → Report quantities / Complete still work |

### Low or cosmetic

| ID | Issue | Where |
|---|---|---|
| B8 | 7 email-preference labels show raw codes: SUBSCRIPTION_EXPIRY, PAYMENT_RECEIVED, REFUND_ISSUED, RENEWAL_INVOICE, INVOICE_OVERDUE, PLAN_CHANGED, PLAN_LIMIT_WARNING. They are also shown to roles with no billing access. | My account, `frontend/src/components/app/notification-preferences.tsx` |
| B9 | A serial's page lists the return inspection (INS-2026-00006, done on 5 returned units) against **every** serial of the batch, e.g. DS-0042, which was never returned. | /traceability/serials/DS-0042 |
| B10 | Unformatted number in a message: "Batch PB-2026-00001 has only **95.000000** available." | Record dispatch |
| B11 | Misleading messages: a negative PO quantity and a non-numeric shift break both show "Enter a number with at most 6 decimal places". | New PO, Add shift |
| B12 | Raw server wording: "must be a future date" (CAPA target), "must be greater than 0" (material issue), "must be a date in the past or in the present" (calibration). | various dialogs |
| B13 | Date and time formats differ: notifications, history and timelines use US "11/25/2026, 9:13:02 PM", while the audit log, support and PDFs use "25 Nov 2026, 10:47 pm". | app-wide |
| B14 | The submitter of a BOM or routing still sees an "Approve" button (the server refuses with a clear message). The dialog text "Note (needed if you are approving your own work)" is confusing when the settings require a separate approver. | BOM / routing / WO approve |
| B15 | After a **failed** calibration, the instrument shows "out of service" and "in date" at the same time. | Calibration detail |
| B16 | A CAPA action owner without CAPA_VIEW sees "No evidence yet." while the attachments API returns 403. The message is misleading and they can't attach evidence. | CAPA page as Inspector |
| B17 | Every page logs a console 404 for `/organizations/current/logo` until a logo is uploaded. | app-wide |
| B18 | The work order page, opened as an Operator, makes a failing call (403 on `/manufacturing/work-centers`). | WO page |
| B19 | Small UX gaps. "Inspect" on an operation's quality gate creates the inspection but stays on the page. The Lot isn't pre-selected when only one reserved lot exists. "One of these serial numbers is already registered." doesn't say which one. "Choose Growth" creates an invoice with no confirmation. Text reads "1 rows" and "1 facilities". | various |
| B20 | Inspection yes/no dropdowns are disabled after submit but aren't greyed out like the number fields. | Inspection page |

### Found in the second pass (26 Nov 2026, see section 6)

| ID | Severity | Issue | Where |
|---|---|---|---|
| **B21** | Medium | **Paying online sends no confirmation to anyone.** The Owner is the only person with SUBSCRIPTION_MANAGE, and `notify()` leaves out whoever acted. So after the Owner pays, nobody gets "Payment received" or "Plan changed", and no receipt email goes out. No such notification was created for BILL-2026-00001 or -00002. | `SubscriptionService.markPaid` → use `notifyIncludingActor` for PAYMENT_RECEIVED and PLAN_CHANGED |
| **B22** | Medium | **"Today" is the UTC date, not the organisation's.** Between 00:00 and 05:30 IST, a receipt dated today is refused: "The date cannot be in the future." (server `@PastOrPresent` uses the JVM clock). The receipt "Received on", PO "Order date" and template "Effective from" fields also default to yesterday. | `purchase-orders/[id]/receive/page.tsx:38`, `purchase-order-form.tsx:50`, `inspection-templates/[id]/page.tsx:124`; backend date checks |
| **B23** | Medium | **A material correction doesn't reach stock transferred to another plant.** After MC-2026-00001 changed LOT-2026-00001's supplier batch from HEAT-A to BS-H2291-B, the Chakan copy LOT-2026-00001-T1 still shows HEAT-A. The correction's impact list doesn't mention the transferred lot either. | Material corrections × stock transfers |
| **B24** | Medium (a11y) | **Dialogs don't hold keyboard focus.** After the last button (Close), Tab moves to the page behind (WCAG 2.4.3). Escape correctly returns focus to the button that opened the dialog. | `components/ui/dialog.tsx` (Base UI), every RecordDialog |
| **B25** | Medium (a11y) | **No "skip to content" link.** The first Tab stop is Notifications, and a keyboard user passes about 40 menu items before reaching the page (WCAG 2.4.1). | `app-shell.tsx` |
| B26 | Low | With wrong Razorpay keys (401 from Razorpay), the payer sees "The payment service did not respond. Please try again." That hides a configuration fault. | Billing → Pay online |
| B27 | Low | Before release, a work order for a **recipe** product says "Will be made from **BOM**: None in effect". After release it correctly says "Recipe, Revision 1". | WO-2026-00003 |
| B28 | Low | A recipe's process parameters (MIX-T mixing temperature, 35 to 45 °C) are not shown on the work order, so the operator never sees them. | WO page / shop floor |
| B29 | Low | A correction of used material shows "Approvals 0 of 1" until the first approval, then "1 of 2". The first approver still sees Approve and Reject afterwards (the server refuses: "A second person must give the other approval."). | Material correction page |
| B30 | Low | Rework: the NCR raised automatically by the shop-floor report (NCR-2026-00006) isn't pre-selected or linked on the rework order, so it stays open on its own. All steps share one work centre. The reinspection shows Quantity 20 (the whole batch) instead of the 3 reworked pieces. | RW-2026-00001, INS-2026-00008 |
| B31 | Low | The NCR raised from a conditional acceptance reads "Disposition: CONDITIONAL_ACCEPTANCE." (raw code). | NCR-2026-00007 |
| B32 | Low (a11y) | axe: the dashboard and analytics bar charts put `aria-label` on role-less `div`s (27 elements), which screen readers ignore. The dashboard "Production" summary nests `dt`/`dd` in extra divs, which breaks the description list. | `/dashboard`, `/analytics` |
| B33 | Low | Small UX gaps. "Sent on" on Send stock starts empty although it is required (other forms default to today). After adding a recipe line, "Add at least one line before submitting." stays on screen. The Receive page opened by URL shows the form to people who cannot receive, and saving gives 403. | various |
| B34 | Low | Recall: marking a customer "Resolved" asks for no note while units are still out. "Edit regulator details" is still offered on a closed recall. | RCL-2026-00001 |

### Fix status, second pass (branch `fix/second-pass-bugs`)

| Bug | Status | Fix |
|---|---|---|
| B21 | ✅ Fixed | "Payment received" and "Plan changed" now reach the person who paid as well (`notifyIncludingActor`). Test: `RazorpayPaymentsIT.returningFromCheckoutAsksRazorpayWhatHappened`. |
| B22 | ✅ Fixed | A new `OrganizationClock` gives "today" in the organisation's time zone. It is used for receipt and calibration dates, CAPA target dates, the template in force, product revisions and facility year. The forms default to the browser's local date (`lib/dates.ts` `today()`). Test: `PurchasingIT.aReceiptDatedTheOrganisationsTodayIsAcceptedAndOneDatedLaterIsNot`. |
| B23 | ✅ Fixed | Correcting a lot's material or supplier batch also corrects the parts transferred to other plants. The impact lists them under "Also corrected at other plants". Test: `StockTransferIT.aCorrectionOfWhatTheLotIsReachesThePartSentToAnotherPlant`. |
| B24 | ✅ Fixed, re-tested | The shared dialog keeps Tab and Shift+Tab inside it, which covers confirmation boxes too. Test: `components/ui/dialog.test.tsx`. |
| B25 | ✅ Fixed, re-tested | A "Skip to content" link is the first Tab stop and moves focus to `<main id="main">`. |
| B26 | ✅ Fixed | A 401 from Razorpay is logged as a key problem, and the payer sees "Online payment is not set up correctly … Please contact support." |
| B27 | ✅ Fixed, re-tested | With nothing in effect, the kind follows how the product is made, e.g. "Recipe: None in effect". Test: `WorkOrderIT.anOrderForARecipeProductSaysRecipeAndShowsTheRecipesConditions`. |
| B28 | ✅ Fixed, re-tested | The work order lists the recipe's process conditions, e.g. "MIX-T – Mixing temperature: 35 to 45 °C". Routing operations already carried them onto the shop floor. Same test. |
| B29 | ✅ Fixed | A correction that needs two approvals says "0 of 2" from the start. Someone who has already approved sees "You have approved this…" instead of the buttons. Test: `MaterialCorrectionIT.aDraftAlreadySaysTwoApprovalsWhenTheMaterialHasGoneFurther`. |
| B30 | ✅ Fixed | The reinspection covers only the reworked quantity and its sample is sized to that (test added to `ProductionQualityIT`). When there is one open NCR, the rework dialog pre-selects it, and the quantity starts at what is still to rework. A step line ending "@ WC-CODE" goes to that work centre. |
| B31 | ✅ Fixed | The NCR says "Disposition: conditional acceptance." |
| B32 | ✅ Fixed, re-tested | Chart bars with a label have `role="img"`. Tile `dt`/`dd` are now direct children of the list's row (the icon is decoration). |
| B33 | ✅ Fixed, re-tested | "Sent on" defaults to today. A refusal on a revision clears once the revision changes. The Receive page shows "You do not have permission to do this." without RECEIPT_MANAGE. |
| B34 | ✅ Fixed, re-tested | Resolving a customer who still holds units needs a reason, which is kept on the customer and in the audit; the UI asks for it. Regulator details cannot be changed on a closed recall, and the button is hidden. Tests: `RecallIT.aCustomerStillHoldingUnitsIsResolvedOnlyWithAReason`, `whatTheRegulatorWasToldCannotChangeOnceTheRecallIsClosed`. |

Tests for the second pass: in the full backend `mvn verify`, unit tests pass, and 767 of 768 integration tests passed on the first run. The one failure was `QualityIT`, which expected the old 400 for a past CAPA target; it now expects 422 with the message, and `QualityIT` passes 26/26. Frontend typecheck and lint are clean, and 220 unit tests pass. Browser re-check: axe finds no violations on /dashboard or /analytics, Tab does not leave a dialog, the skip link is the first stop, and the Operator gets "You do not have permission" on Receive.

### Observations (by design or for product review)

- **Supplier approval has no maker/checker:** the Owner submitted and approved the same supplier. Inspections, NCRs, CAPAs, BOMs, routings and WOs do enforce it.
- "Assign to", CAPA owner, action owner and lead auditor lists include every active user, including Viewer and Operator, who can't act on NCRs or CAPAs.
- Rejected shop-floor quantities can be saved without a defect category.
- A "new" password identical to the old one is accepted.
- Duplicate serials pasted in one list are merged silently.
- Searching a customer name ("Tata") in traceability finds nothing; search covers lots, batches, serials, materials and products only.
- The supplier detail page doesn't show the supplier's Category (it is saved and visible in Edit).
- The purchase order page doesn't show unit prices or a total. No email goes to the supplier when a PO is issued.
- The inspection queue shows the internal lot number but not the supplier's heat or batch number.
- Production analytics "Efficiency 31,818%": my operations ran for minutes against 17.5 planned hours, so this is a test artifact, but the figure has no upper bound.

---

## 3. Test accounts and data

Organisation **Sahyadri Auto Components** (GSTIN 27ABCDE1234F1Z5). All passwords are `correct-horse-battery`.

| Role | Person | Email |
|---|---|---|
| Organisation Owner | Rohan Kulkarni | owner-1795620316444@sahyadri.test |
| Organisation Admin | Anita Desai | admin-1795620669088@sahyadri.test |
| Plant Manager | Suresh Patil | plant-1795620669088@sahyadri.test |
| Production Manager | Kiran Shinde | prodmgr-1795620669088@sahyadri.test |
| Production Operator | Ganesh More | operator-1795620669088@sahyadri.test |
| Production Planner | Pooja Nair | planner-1795620669088@sahyadri.test |
| Quality Engineer | Amit Joshi | qe-1795620669088@sahyadri.test |
| Quality Inspector | Sneha Pawar | inspector-1795620669088@sahyadri.test |
| Quality Manager | Meera Iyer | qm-1795620669088@sahyadri.test |
| Supplier Manager | Rahul Mehta | supplier-1795620669088@sahyadri.test |
| Viewer (later also Quality Inspector) | Neha Gupta | viewer-1795620669088@sahyadri.test |
| Auditor | Farhan Shaikh | auditor-1795620669088@sahyadri.test |

A second organisation, **Rival Forge Pvt Ltd** (owner2-…@rivalforge.test), was created only for the tenant-isolation test.

Records created along the way: facilities PUNE-1 (Bhosari) and CHAKAN (archived); suppliers BSL (approved) and KF (rejected); materials EN8-BAR, BRG-6205, OIL-68; products DS-100 and DS-100-M; templates EN8-IQC, DS-IPQC, DS-FQC; PO-2026-00001; GRN-2026-00001 to -00003; LOT-2026-00001 to -00005; INS-2026-00001 to -00007; NCR-2026-00001 to -00005; CAPA-2026-00001; PP-2026-00001; WO-2026-00001; PB-2026-00001 with serials DS-0001 to DS-0095; DSP-2026-00001 and -00002; RET-2026-00001; RCL-2026-00001; instruments MIC-01 and VC-02; AUD-2026-00001; invoice BILL-2026-00001 (unpaid); one support request. The permission probe created records named "Probe"; they were archived afterwards.

---

## 4. Role-based testing

### 4.1 Creating role-wise users

| # | Scenario | Result |
|---|---|---|
| R1 | Owner invites one user per role (11 invitations) from Users → Invite user | ✅ All sent; trial (Professional) shows 12 of 50 users |
| R2 | Invite with no email | ✅ "Enter a valid email" |
| R3 | Invite an address that already has a pending invitation | ✅ "This address already has a pending invitation." |
| R4 | Each person opens the email link in their own browser: "Join Sahyadri Auto Components" | ✅ |
| R5 | Accept with a 5-character password | ✅ "at least 10 characters" |
| R6 | Accept → lands on the dashboard, "Welcome back, &lt;name&gt;" | ✅ all 11 |
| R7 | Operator and Inspector dashboards show "Use the menu to reach the work assigned to you." instead of KPI cards | ✅ |

### 4.2 What each role can see and do (UI sweep of 37 pages × 12 roles)

`+n` = page loads with n action buttons · `r` = read-only · `X` = "You do not have permission to do this."

| Page | Owner | Admin | Plant Mgr | Prod Mgr | Operator | Planner | QE | Inspector | QM | Supplier Mgr | Viewer | Auditor |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Facilities | +1 | +1 | +1 | r | r | r | r | r | r | r | r | r |
| Suppliers | +1 | +1 | r | X | X | X | r | r | r | +1 | r | r |
| Materials | +1 | +1 | r | +1 | r | r | +1 | r | r | r | r | r |
| Products | +1 | +1 | r | +1 | r | r | +1 | r | r | r | r | r |
| Inspection templates | +1 | +1 | r | r | X | X | +1 | r | +1 | X | r | r |
| Categories | +6 | +6 | +2 | +3 | r | r | +5 | r | +2 | +3 | r | r |
| Purchase orders | r* | r* | r | r | X | r | r | r | r | r* | r | r |
| Receipts | r | r | r | r | X | r | r | r | r | r | r | r |
| Inspections | r | r | r | r | X | X | r | r | r | r | r | r |
| NCRs | +3 | +3 | r | +3 | X | X | +3 | +3 | +3 | r | r | r |
| CAPAs | +1 | +1 | r | X | X | X | +1 | X | +1 | r | r | r |
| Work centres / Machines / BOMs / Routings | +1 | +1 | +1 | +1 | X | r | r | r | r | X | r | r |
| Production plans / Work orders | +1 | +1 | +1 | +1 | r | +1 | r | r | r | X | r | r |
| Shop floor | r | r | r | r | r | r | r | r | r | X | r | r |
| Downtime | +1 | +1 | +1 | +1 | X | r | r | r | r | X | r | r |
| Production batches / Stock transfers / Dispatches | +1 | +1 | r | +1 | r | r | +1 | r | +1 | X | r | r |
| Customers | +1 | +1 | r | +1 | r | r | +1 | r | +1 | r | r | r |
| Returns / Corrections | r | r | r | r | r | r | r | r | r | X | r | r |
| Recalls | r | r | r | X | X | X | r | X | r | X | r | r |
| Calibration | +1 | +1 | r | r | X | X | +1 | r | +1 | X | r | r |
| Audits / Audit checklists | +1 | +1 | r | X | X | X | X | X | +1 | X | r | +1 |
| Analytics / Reports | r | r | r | r | X | r | r | X | r | r | r | r |
| Users | +4 | +4 | X | X | X | X | X | X | X | X | X | X |
| Roles | +1 | +1 | r | r | r | r | r | r | r | r | r | r |
| Organisation settings | +2 | +2 | r | r | r | r | r | r | r | r | r | r |
| Billing | +4 | +1 ⚠ | +1 ⚠ | +1 ⚠ | +1 ⚠ | +1 ⚠ | +1 ⚠ | +1 ⚠ | +1 ⚠ | +1 ⚠ | +1 ⚠ | +1 ⚠ |
| Audit log | +1 | +1 | +1 | X | X | X | X | X | +1 | X | X | +1 |
| Control Center (/platform) | X | X | X | X | X | X | X | X | X | X | X | X |

\* "New purchase order" is a link, not a button. ⚠ = bug B5 ("Cancel subscription" shown to non-owners).

**The menu matches permissions:** for every role, each page that would answer "no permission" is also missing from that role's menu. Billing appears only in the Owner's menu; Users and Roles only in the Owner's and Admin's.

### 4.3 Does the server enforce the same rules? (direct API calls with each role's session)

Each of the 11 non-owner roles tried: create facility, supplier, material, product, inspection template, invitation, role, work centre and customer; cancel the subscription; read the audit log.

| Result | |
|---|---|
| Calls | 115 |
| Allowed when the role has the permission (201/200) | ✅ every time |
| Refused when it doesn't (403 "You do not have permission to do this.") | ✅ every time |
| Mismatches | **0** |

### 4.4 Segregation of duties and privilege checks

| # | Scenario | Result |
|---|---|---|
| S1 | Inspector submits an inspection: no Approve button for them | ✅ |
| S2 | Owner (has every permission) inspects, then tries to approve their own: button hidden; API → "An inspection must be reviewed by someone other than its inspector." | ✅ |
| S3 | Production Manager submits a BOM or routing, then tries to approve it → "You submitted this revision, so someone else must approve it." | ✅ (B14: button still shown) |
| S4 | QE (CAPA owner) submits a CAPA for verification: no Verify button; QM verifies | ✅ |
| S5 | QE has no Publish button on templates; QM publishes | ✅ |
| S6 | Planner can't approve their own plan or WO; Production Manager approves | ✅ |
| S7 | Operator can't start production (supervisor action) | ✅ |
| S8 | Auditor can't close the audit they ran; it needs AUDIT_CLOSE | ✅ |
| S9 | Admin tries to give themselves Organisation Owner → "You cannot grant permissions you do not hold." | ✅ |
| S10 | Admin tries to deactivate the only Owner → "An organisation must keep at least one active owner." | ✅ |
| S11 | Owner removes their own Owner role → same message | ✅ |
| S12 | Edit roles with every role unticked: Save is disabled | ✅ |
| S13 | Deactivated user: open session gets kicked to /login (API 401); sign-in → "This account does not belong to an active organisation."; reactivating restores access | ✅ |
| S14 | A CAPA action owner without CAPA permissions (Inspector) opens the CAPA from her notification and can complete only her own action | ✅ (B16) |
| S15 | Supplier submit + approve by the same Owner | ⚠ allowed (observation) |

### 4.5 Tenant isolation

| # | Scenario | Result |
|---|---|---|
| T1 | A second organisation opens Sahyadri's NCR, inspection, WO, batch, supplier, CAPA and recall URLs | ✅ "This record does not exist." |
| T2 | Same records by API | ✅ 404 RESOURCE_NOT_FOUND |
| T3 | The second organisation's NCR list | ✅ empty |
| T4 | Trace PB-2026-00001 / serial DS-0042 from the other organisation | ✅ "This record does not exist." |
| T5 | Write to another organisation's NCR | ✅ refused |

---

## 5. Workflows and scenarios

✅ pass · ❌ bug · ⚠ issue or observation

### W1 Public pages (signed out)

| # | Scenario | Result |
|---|---|---|
| 1.1 | Landing page renders: hero, how it works, controls, product, security, pricing, footer | ✅ |
| 1.2 | Pricing loads from the API (Starter ₹2,999, Growth ₹5,999, Professional ₹9,999 a month) after a brief "Loading prices…" | ✅ |
| 1.3 | /status → "All systems operational", version shown | ✅ |
| 1.4 | /docs and /docs/workflows: flowcharts and topics | ✅ |
| 1.5 | /login renders; "Forgot your password?" and "Create an organisation" links | ✅ |
| 1.6 | /platform for a tenant user → "You do not have access to the Control Center." | ✅ |

### W2 Sign-up, email verification and sign-in

| # | Scenario | Result |
|---|---|---|
| 2.1 | Submit an empty form → errors on all four fields | ✅ |
| 2.2 | Bad email / 3-character password → "Enter a valid email" / "Use at least 10 characters" | ✅ |
| 2.3 | Valid sign-up → "Check your inbox"; Mailpit gets "Confirm your ProductionOS email address" | ✅ |
| 2.4 | Sign in before verifying → "Confirm your email address" page with "Send a new link" | ✅ |
| 2.5 | Verify link → "Confirm email" → confirmed | ✅ |
| 2.6 | Reuse the verify link → "This link is not valid or has expired." | ✅ |
| 2.7 | Wrong password → "Email or password is incorrect." | ✅ |
| 2.8 | Correct sign-in → /onboarding | ✅ |

### W3 Onboarding

| # | Scenario | Result |
|---|---|---|
| 3.1 | Bad GSTIN → "Must be a 15 character GSTIN" | ✅ |
| 3.2 | Legal name, GSTIN, address, contact → Continue → dashboard "Welcome back, Rohan" with empty states | ✅ |

### W4 Facilities

| # | Scenario | Result |
|---|---|---|
| 4.1 | Save empty → "Code is required", "Name is required" | ✅ |
| 4.2 | State → City cascade (Maharashtra → Pune) | ✅ |
| 4.3 | Duplicate code → "A facility with this code already exists." | ✅ |
| 4.4 | Departments (MACH, QA) and production line L1 | ✅ |
| 4.5 | Shifts: General (09:00–17:30), Night (22:00–06:00, crosses midnight), Weekend (Sat/Sun only) | ✅ |
| 4.6 | Shift break "abc" | ⚠ B11 wording |
| 4.7 | Holiday 25 Dec; the same date again → "2026-12-25 is already a holiday here." | ✅ |
| 4.8 | Weekly off Sunday + alternate 2nd/4th Saturday; shown in the Holiday calendar | ✅ |
| 4.9 | Edit name; archive asks "Archive this facility?"; hidden from the list; "Show archived" offers Restore | ✅ |

### W5 Master data: categories, suppliers, materials, products

| # | Scenario | Result |
|---|---|---|
| 5.1 | Supplier, product and defect categories (DIM, SURF) | ✅ |
| 5.2 | Suppliers BSL and KF with GSTIN, category, email, phone, state/city → draft | ✅ |
| 5.3 | Bad supplier email → "Enter a valid email" | ✅ |
| 5.4 | Supplier contact added (name, designation, email, primary) | ✅ |
| 5.5 | Submit → confirm → pending approval → Approve → approved (Block offered) | ✅ |
| 5.6 | Reject KF: reason required ("Reason is required"); status note shown; can resubmit | ✅ |
| 5.7 | Materials: EN8 (KG, inspect on receipt), bearing (PCS, inspect), oil (LTR, 365-day shelf life); unit required | ✅ |
| 5.8 | Products DS-100 (finished) and DS-100-M (semi-finished), revision A | ✅ |
| 5.9 | Product spec 24.95/25/25.05 mm; min > max → "The minimum cannot be above the maximum." | ✅ |
| 5.10 | Map materials to the product (1.2 kg EN8, 2 bearings); new revision B | ✅ |
| 5.11 | Upload a PDF document; upload an .exe → "Only PDF, PNG, JPEG and Office files can be uploaded." | ✅ |

### W6 Purchasing (Supplier Manager)

| # | Scenario | Result |
|---|---|---|
| 6.1 | Supplier list offers only approved suppliers (rejected KF hidden) | ✅ |
| 6.2 | Empty save → "Choose a supplier / facility / material" | ✅ |
| 6.3 | Expected date before order date → "The delivery date cannot be before the order date." | ✅ |
| 6.4 | Negative quantity | ⚠ B11 |
| 6.5 | 3-line draft PO-2026-00001 → Issue → "can no longer be edited" → issued | ✅ |

### W7 Goods receipt (Quality Inspector)

| # | Scenario | Result |
|---|---|---|
| 7.1 | Receive 3 lines with supplier batch, quantity, expiry → GRN-2026-00001; one internal LOT per line; lots that need no inspection are marked "not required" | ✅ |
| 7.2 | Second partial delivery (HEAT-B 300 kg) → PO "partially received" 900/1000 | ✅ |
| 7.3 | Over-receipt → "Line 1: only 100 remains to be received." | ✅ |
| 7.4 | QM is notified "GRN-…: n lot(s) to inspect" | ✅ |

### W8 Inspection templates (QE builds, QM publishes)

| # | Scenario | Result |
|---|---|---|
| 8.1 | Template with fixed sampling (3), numeric DIA/HARD and yes/no CRACK (critical) | ✅ |
| 8.2 | HARD min > max → "HARD: the minimum is above the maximum." | ✅ |
| 8.3 | QE: no Publish button; QM: Publish → effective date → Version 1 | ✅ |
| 8.4 | Final-release checkpoint given an in-process template → "Final release takes a final (FQC) inspection template." | ✅ |

### W9 Incoming inspection, pass path (Inspector → QM)

| # | Scenario | Result |
|---|---|---|
| 9.1 | Queue lists waiting lots with lot, quantity, supplier, GRN | ✅ (supplier heat not shown) |
| 9.2 | Submit with nothing recorded → "DIA is incomplete; HARD is incomplete; CRACK is incomplete." | ✅ |
| 9.3 | Non-numeric value → "Check these values: HARD sample 2." | ✅ |
| 9.4 | Live pass/fail per sample; yes/no shows "hold / Reviewer decides" | ✅ |
| 9.5 | Submit → values locked | ✅ (B20 cosmetic) |
| 9.6 | QM: Approve needs a Result when a parameter is reviewer-decided | ✅ |
| 9.7 | Record disposition Accept → completed | ✅ |
| 9.8 | Header after decision | ❌ B3 |
| 9.9 | Inspector notified (in-app + email) of the disposition | ✅ |

### W10 Incoming inspection, fail path → NCR

| # | Scenario | Result |
|---|---|---|
| 10.1 | QM sends back: "What needs correcting" is required; inspector notified in-app and by email; fields editable again | ✅ |
| 10.2 | Resubmit, still failing; approval dialog has only a Note (result is evaluated) | ✅ |
| 10.3 | Disposition options for a fail: Reject / Hold / Rework / Conditional acceptance (no Accept) | ✅ |
| 10.4 | Reject → NCR-2026-00001 raised automatically (major, source, supplier, lot, rejected quantity, failed parameters) | ✅ |
| 10.5 | Rejected lot excluded from "free in facility" stock | ✅ |

### W11 NCR → CAPA → closure (QM, QE, Inspector, Supplier Manager)

| # | Scenario | Result |
|---|---|---|
| 11.1 | QM assigns the NCR to the QE; QE notified | ✅ |
| 11.2 | QE records containment → root cause + defect category | ✅ |
| 11.3 | Request verification without a closed CAPA → "A major NCR needs a closed CAPA." | ✅ |
| 11.4 | Raise CAPA with a past target date | ✅ refused (B12 wording) |
| 11.5 | Analysis: 5 Whys + 6M fishbone + root cause; Save analysis persists | ✅ |
| 11.6 | Corrective action → Supplier Manager; preventive action → Inspector; Start implementation | ✅ |
| 11.7 | Action owners notified; each completes only their own action with "What was done" | ✅ |
| 11.8 | QE submits for verification → QM verifies effectiveness (Effective / Not effective) → Close CAPA | ✅ |
| 11.9 | QE requests NCR verification → QM closes the NCR; full history with names and notes | ✅ |
| 11.10 | Dashboard and analytics update (pass rate, NCRs closed, recent activity) | ✅ |

### W12 Manufacturing setup (Production Manager, QM)

| # | Scenario | Result |
|---|---|---|
| 12.1 | Work centres with capacity and ₹/hour costs; machines under each | ✅ |
| 12.2 | BOM seeded from the product's material list | ✅ |
| 12.3 | Routing ops 10 and 20; duplicate step → "Step 10 is already used." | ✅ |
| 12.4 | Self-approval refused; QM approves; effective from today | ✅ |
| 12.5 | BOM explosion for 100 = 120 kg EN8 + 200 bearings | ✅ |
| 12.6 | Quality checkpoints: in-process "before op 20 completes, must pass" + final release | ✅ |

### W13 Planning → work order (Planner, Production Manager)

| # | Scenario | Result |
|---|---|---|
| 13.1 | Plan with period end before start → "The period cannot end before it starts." | ✅ |
| 13.2 | Plan line 200 PCS; Production Manager approves the plan | ✅ |
| 13.3 | Raise work order from the line: quantity prefilled, planned start required | ✅ |
| 13.4 | Submit → approve → release (freezes BOM/routing revisions) | ✅ |
| 13.5 | Planned cost checked by hand: material ₹34,260, labour ₹5,775, machine ₹14,350, overhead ₹2,625 = ₹57,010 (₹570.10 a unit) | ✅ |
| 13.6 | "Reserve what is free" reserves against the right lots | ✅ |
| 13.7 | Production Manager starts production → batch PB-2026-00001 opens, op 10 ready on the shop floor | ✅ |

### W14 Shop floor (Operator)

| # | Scenario | Result |
|---|---|---|
| 14.1 | Over-issue → "Line 1 needs 120 and 0 is already issued. Issuing more than an order needs is switched off in Configuration." | ✅ |
| 14.2 | Issue material | ✅ (B19: lot not pre-selected; B12) |
| 14.3 | Start op → choose machine → in progress | ✅ |
| 14.4 | Report quantities that don't add up | ❌ B4 |
| 14.5 | Report more than the order → "Operation 10 can take 40 more: the order is for 100." | ✅ |
| 14.6 | Pause for Changeover → Resume works | ✅ |
| 14.7 | Pause for Machine breakdown → machine marked broken down; Resume refused with a clear message | ✅ |
| 14.8 | Report or complete while paused for breakdown | ⚠ B7 |
| 14.9 | Setup time longer than run time → refused | ✅ |
| 14.10 | Complete op 20 before its must-pass check → "…cannot be completed until its quality checks pass." | ✅ |
| 14.11 | Inspector runs the gate inspection; QM accepts; gate passed; op completes | ✅ (B19 UX) |
| 14.12 | Rejections and scrap raise NCRs automatically (NCR-2026-00002, -00003) | ✅ |
| 14.13 | Production Manager repairs machines (Change status → Available); downtime log filled automatically from the shop floor | ✅ |

### W15 Finished goods and final release

| # | Scenario | Result |
|---|---|---|
| 15.1 | Receive finished goods: "Why material use was off" required when usage differs; 95 of 100 received | ✅ |
| 15.2 | Final inspection from the batch page → batch released | ✅ |
| 15.3 | Work order final-release gate / closing | ❌ **B1** |
| 15.4 | Production record PDF | ❌ B6 |

### W16 Serials, customers, dispatch and returns

| # | Scenario | Result |
|---|---|---|
| 16.1 | 96 serials for 95 units → "This batch has more serial numbers than units." | ✅ |
| 16.2 | Re-adding an existing serial → refused | ✅ (B19 wording) |
| 16.3 | Customers TML and MM | ✅ |
| 16.4 | Dispatch more than available → refused | ✅ (B10 format) |
| 16.5 | Serial count ≠ quantity → "The number of serial numbers must match the quantity." | ✅ |
| 16.6 | Already-shipped serials → "A serial number is unknown, already shipped, or from another batch." | ✅ |
| 16.7 | Confirm shipment dated before dispatch → "The shipping date must fall between the dispatch and today." | ✅ |
| 16.8 | Customer return of 5 serials with RMA and reason → RET-2026-00001; stock not counted until inspected | ✅ |
| 16.9 | Inspect return → accept → return closed, available stock 5 → 10 | ✅ |

### W17 Traceability and recall

| # | Scenario | Result |
|---|---|---|
| 17.1 | Search by supplier heat, lot and serial | ✅ |
| 17.2 | Forward trace from the lot: batch, both customers, return, serials | ✅ |
| 17.3 | Backward trace from the batch: both lots, supplier, heat, GRN, PO | ✅ |
| 17.4 | Serial page: batch, inspections, where it went (shipped / returned) | ✅ (B9) |
| 17.5 | Global search box → grouped results | ✅ |
| 17.6 | Viewer: trace pages with no action buttons | ✅ |
| 17.7 | Recall report: TML 60 / 5 returned / 55 out, MM 30 out | ✅ |
| 17.8 | QE starts recall RCL-2026-00001 (85 units still with customers) | ✅ |
| 17.9 | Request closure while customers are unresolved → refused with the customer list | ✅ |
| 17.10 | Log contact attempt; record acknowledgements; counters update | ✅ |

### W18 Calibration (QE, Inspector, QM)

| # | Scenario | Result |
|---|---|---|
| 18.1 | Register MIC-01 (in date) and VC-02 (overdue); list flags overdue | ✅ |
| 18.2 | Overdue instrument isn't offered in an inspection | ✅ |
| 18.3 | In-date instrument added to an inspection | ✅ |
| 18.4 | Calibration dated in the future → refused | ✅ (B12 wording) |
| 18.5 | Pass calibration → due date rolls forward one year | ✅ |
| 18.6 | Fail calibration → out of service, NCR-2026-00004, inspection INS-2026-00007 flagged "calibration review pending" | ✅ (B15) |
| 18.7 | QM decides "Result stands" with a reason → "all decided" | ✅ |

### W19 Audits (Auditor, QM)

| # | Scenario | Result |
|---|---|---|
| 19.1 | Checklist from lines with ISO clauses ("Question \| 8.4.2") | ✅ |
| 19.2 | Plan a supplier audit with checklist and lead auditor (deactivated users not offered) | ✅ |
| 19.3 | Complete with unanswered questions → "4 checklist question(s) have no answer." | ✅ |
| 19.4 | Answer conforming / nonconforming / N/A; answers persist | ✅ |
| 19.5 | Major finding → NCR-2026-00005 raised automatically | ✅ |
| 19.6 | Request closure while the NCR is open → "NCR-2026-00005 is still open." | ✅ |

### W20 Analytics and reports

| # | Scenario | Result |
|---|---|---|
| 20.1 | Analytics KPIs match what was done (6 decided, 83.3% pass, 4 open NCRs, supplier scorecard, Pareto) | ✅ |
| 20.2 | Control charts: X̄-R, Cp/Cpk marked preliminary | ✅ |
| 20.3 | Production analytics: OEE, scrap, rework, downtime | ✅ (efficiency has no cap) |
| 20.4 | Inspection, NCR, batch traceability and OEE reports: PDF and Excel downloads are valid files | ✅ |
| 20.5 | Inspection report page and WO production record PDF render cleanly | ✅ (B6) |

### W21 User administration (Admin)

| # | Scenario | Result |
|---|---|---|
| 21.1 | Revoke a pending invitation | ✅ |
| 21.2 | Add a role to a user | ✅ |
| 21.3 | Restrict a user to facilities | ✅ dialog |
| 21.4 | Deactivate / reactivate a user | ✅ |
| 21.5 | Escalation and last-owner protections | ✅ (S9–S11) |

### W22 Account, settings, billing, support

| # | Scenario | Result |
|---|---|---|
| 22.1 | My account: profile read-only for non-admins; email preferences | ✅ (B8) |
| 22.2 | Change password: too short refused; valid change keeps you signed in and sends an email; old password stops working | ✅ |
| 22.3 | Wrong current password | ❌ B2 |
| 22.4 | Sign out → /login; protected pages redirect | ✅ |
| 22.5 | Forgot password gives the same message for known and unknown emails | ✅ |
| 22.6 | Reset link → new password works; reusing the link → "not valid or has expired" | ✅ |
| 22.7 | Logo: over 256 KB → "The logo must be 256 KB or smaller."; small PNG → shown | ✅ |
| 22.8 | Organisation profile edit persists; organisation settings (review rules, NCR/CAPA rules, status names) | ✅ |
| 22.9 | Billing (Owner): Starter refused, "The Starter plan allows 5 users. Remove some users or pending invitations first." | ✅ |
| 22.10 | Choose Growth → invoice BILL-2026-00001 ₹7,078.82 (₹5,999 + 18% GST), Razorpay test mode | ✅ (no confirm, B19) |
| 22.11 | Billing as a non-owner | ❌ B5 |
| 22.12 | Support request: Subject and message required; request created with a thread | ✅ |
| 22.13 | Notifications, holiday calendar, state holidays, scheduling, rework orders, corrections, stock transfers, audit log pages load | ✅ |
| 22.14 | Phone width (390 px): dashboard, shop floor and inspection have no sideways scrolling | ✅ |

---

## 6. Second pass: the areas left out of the first pass (26 Nov 2026)

Same stack and accounts, on branch `fix/supplier-approval-assignees`. Razorpay ran in **test mode** with the organisation's test keys (in the git-ignored `.env`). Each step was done by the role that owns it.

### W23 Paying an invoice through Razorpay (Owner)

| # | Scenario | Result |
|---|---|---|
| 23.1 | Old test keys: Razorpay answers 401; the payer sees "The payment service did not respond." | ⚠️ B26 |
| 23.2 | Pay ₹7,078.82 online → Razorpay Checkout (Test Mode) → contact number → Netbanking → bank → Razorpay's demo bank page → Success | ✅ |
| 23.3 | Back in the app: "Payment received. Thank you."; BILL-2026-00001 **paid**; payment history shows `pay_U4VMV4nUBGX2rV`; plan **Growth, Active** | ✅ |
| 23.4 | Invoice PDF: "Paid", GSTIN, ₹5,999 + GST 18% ₹1,079.82 = ₹7,078.82 | ✅ |
| 23.5 | Upgrade Growth → Professional: confirmation dialog; BILL-2026-00002 ₹4,720.57 = ₹9,999 less ₹5,998.52 credit for unused Growth time, plus GST; paid the same way; plan **Professional, Active** | ✅ |
| 23.6 | "Payment received" / "Plan changed" notification or email to anyone | ❌ B21 |

### W24 Closing the audit (Auditor, QE, QM)

| # | Scenario | Result |
|---|---|---|
| 24.1 | Finding NCR-2026-00005 → assigned to QE → containment, investigation → CAPA-2026-00002 | ✅ |
| 24.2 | "Finish analysis" with an empty root cause → "Required" | ✅ |
| 24.3 | Supplier Manager completes the CAPA action; QE submits verification; QM verifies effective and closes CAPA; QE requests NCR verification; QM closes NCR | ✅ |
| 24.4 | On the **Growth** plan, nobody (not even the QM) sees audit closure buttons. Audits belong to the Advanced feature set, but the page still opened and listed audits. Closure appeared after the move to Professional. | ⚠️ the page should say the plan does not include it |
| 24.5 | Auditor: Request closure (note) → QM: "Approve closing this audit?" → AUD-2026-00001 **closed** | ✅ |

### W25 Closing the recall (QE, QM)

| # | Scenario | Result |
|---|---|---|
| 25.1 | Both customers moved to Resolved (no note asked, units still out) | ⚠️ B34 |
| 25.2 | Request closure without the acceptance box → "This recall cannot close yet: Goods remain with customers. Confirm this and explain it in the note." | ✅ |
| 25.3 | With the box and a note → QM approves → RCL-2026-00001 **closed**; "Edit regulator details" still shown | ✅ / ⚠️ B34 |

### W26 Stock transfers between plants (Production Manager, QE)

| # | Scenario | Result |
|---|---|---|
| 26.1 | Restore archived CHAKAN facility (Professional allows 5) | ✅ |
| 26.2 | Send stock: empty → each field "is required"; "Sent on" not pre-filled | ✅ / ⚠️ B33 |
| 26.3 | Same plant both sides → "A transfer goes to another plant." | ✅ |
| 26.4 | 5,000 from a lot with 480 free → "Batch LOT-2026-00001 has only 480 free." | ✅ |
| 26.5 | 100 KG EN8 PUNE-1 → CHAKAN → STR-2026-00001 "in transit"; free stock at PUNE-1 drops to 380 | ✅ |
| 26.6 | Receive at Chakan → new lot **LOT-2026-00001-T1** (100 free at CHAKAN) | ✅ |
| 26.7 | Second transfer (5 L oil) → Cancel with reason → "cancelled"; the oil stays at PUNE-1 (20 free) | ✅ |

### W27 Material corrections (QE requests; QM and Plant Head approve)

| # | Scenario | Result |
|---|---|---|
| 27.1 | From the lot's trace page: Request correction; empty → "Why this record is wrong is required" | ✅ |
| 27.2 | MC-2026-00001: supplier batch HEAT-A → BS-H2291-B; impact shows PB-2026-00001 and the two customers holding the material; submit | ✅ |
| 27.3 | Requester has no Approve button | ✅ |
| 27.4 | QM approves without ticking "I have reviewed the impact" → "This material has gone further than the lot. Review and acknowledge the impact." | ✅ |
| 27.5 | QM approves (1 of 2), Plant Head approves (2 of 2) → **applied**; lot trace shows BS-H2291-B and the correction history | ✅ (counts ⚠️ B29) |
| 27.6 | Transferred lot LOT-2026-00001-T1 still HEAT-A | ❌ B23 |
| 27.7 | MC-2026-00002 rejected by QM with a note → "rejected", decision note shown, lot unchanged | ✅ |

### W28 Rework order end to end (Planner, Production Manager, Operator, QE, Inspector, QM)

| # | Scenario | Result |
|---|---|---|
| 28.1 | WO-2026-00002 (20 × DS-100) planned, approved, released, started; Operator reports op 10: 16 good, 4 rejected for rework → NCR-2026-00006 raised automatically | ✅ |
| 28.2 | QE: Raise rework order, 6 PCS → "Operation 10 has 4 marked for rework that is not on a rework order yet."; 4 PCS → RW-2026-00001 "open" | ✅ |
| 28.3 | Raiser sees only Cancel; Production Manager approves with a note | ✅ |
| 28.4 | Operator: Start rework → Complete before the steps → "Step 1 is not done yet."; marks 3 steps done; 4 reworked + 1 scrapped refused; 3 reworked + 1 scrapped → "awaiting inspection" | ✅ |
| 28.5 | QE: Reinspect → INS-2026-00008 (IPQC); Inspector records; QM approves and Accepts | ✅ (quantity ⚠️ B30) |
| 28.6 | QE: "Close on the reinspection" → **closed**; WO quality: "reworked and accepted 3 · scrapped 1"; operation 20 now has 19 waiting (16 + 3) | ✅ |

### W29 Recipes / process manufacturing (Production Manager, Plant Head, Owner, Planner)

| # | Scenario | Result |
|---|---|---|
| 29.1 | Discrete model: recipe for DS-100-M → "DS-100-M is made by discrete manufacturing, so it takes a BOM, not a recipe." | ✅ |
| 29.2 | Manufacturing settings → Hybrid; product CB-200 Coolant blend (LTR) → "Set how it is made" → Process | ✅ |
| 29.3 | Recipe revision 1, batch 200 L; submit with no lines → "Add at least one line before submitting." | ✅ |
| 29.4 | Line OIL-68 10 L; parameter lower 45 > upper 35 → "The lower limit is above the upper limit."; MIX-T 35 to 45 °C, target 40 | ✅ |
| 29.5 | Scale to 500 L → 25 L oil | ✅ |
| 29.6 | Submitter sees only "Send back"; send back with a note → draft "Sent back: …"; resubmit; Plant Head approves → **approved, in effect from 2026-11-26** | ✅ |
| 29.7 | WO-2026-00003 (100 L CB-200) planned 25 Nov → release refused "CB-200 has no approved recipe in effect on 2026-11-25." Rescheduled to 26 Nov → released, "Made from Recipe, Revision 1", OIL-68 needed 5 L, planned cost ₹1,550 (₹15.50/L) | ✅ (B27, B28) |

### W30 AQL sampling (ISO 2859-1) and conditional acceptance (QE, QM, Inspector)

| # | Scenario | Result |
|---|---|---|
| 30.1 | QE: template BRG-IQC for BRG-6205, Sampling = AQL 1.0, level II, BORE 24.990 to 25.000 mm; QM publishes | ✅ (effective date ⚠️ B22) |
| 30.2 | PO-2026-00002 (500 bearings) issued; receipt dated today refused "The date cannot be in the future." | ❌ B22 |
| 30.3 | Received as Inspector (QE gets 403 by URL, button correctly hidden) → LOT-2026-00006; Inspect → "AQL 1, level II, normal inspection, letter H: accept up to 1 nonconforming, reject from 2", **50 samples** (correct per Table II-A) | ✅ |
| 30.4 | 48 in tolerance, 2 at 25.004 → **fail** | ✅ |
| 30.5 | QM: dispositions offered for a fail: Reject, Hold, Rework, Conditional acceptance (no Accept) | ✅ |
| 30.6 | Conditional acceptance without justification → "Conditional acceptance needs a justification." With it → completed; NCR-2026-00007 raised; lot usable (500 free) | ✅ (B31) |
| 30.7 | Second lot LOT-2026-00007 fails (3 of 50) → Reject → Bharat Steels moves to **tightened** ("two rejections in five lots") | ✅ |
| 30.8 | Third lot LOT-2026-00008 → "tightened inspection … accept up to 1, reject from 2", **80 samples** (Table II-B, letter H at AQL 1.0 points to n = 80) | ✅ |

### W31 Offline inspection (Inspector)

| # | Scenario | Result |
|---|---|---|
| 31.1 | Network cut on INS-2026-00011 → "No connection. Results you save are kept on this device and sent when it is back." | ✅ |
| 31.2 | 10 samples + Save offline → "10 values waiting to be sent", each "On this device, not sent yet" | ✅ |
| 31.3 | Sign out while values wait → "INS-2026-00011 has inspection values not sent yet. They stay on this device and are sent only when you sign in here again. Sign out anyway?"; Cancel keeps the session | ✅ |
| 31.4 | Tab closed, connection restored, new tab → queue sent by itself; server has the 10 results; form shows them | ✅ |
| 31.5 | There is no service worker, so a page can't be opened or reloaded offline. The inspection must already be open when the connection drops. | ℹ️ limitation |

### W32 Other browsers (same 8-step smoke: sign in, menu → NCR, 80-input inspection, dialog with date, select and validation, global search, traceability, report PDF, sign out)

| Browser | Result |
|---|---|
| Chromium (Playwright build) | ✅ 8/8, no console errors, no failed calls |
| Firefox 157 | ✅ 8/8, no console errors, no failed calls; layout matches Chromium |
| WebKit (Safari engine), headless Linux | ⚠️ Not conclusive. The session cookie is `Secure`, and WebKit drops Secure cookies on `http://localhost` (Chromium and Firefox accept them), so every page bounced to /login. With the cookie copied in without Secure: full page loads, forms, dates, selects, search, traceability, PDF and sign-out work. Client-side link navigation (menu → NCRs, Report) still hangs, and Next.js prefetches report "access control checks". Check in real Safari on UAT over HTTPS before treating this as an app bug. |

### W33 Accessibility

| # | Check | Result |
|---|---|---|
| 33.1 | axe-core WCAG 2.1 A/AA on 15 screens: sign-in, register, NCR list and detail, the 80-input inspection, Send stock dialog with errors, work order, rework order, recipe, traceability, new PO, billing, notifications | ✅ clean |
| 33.2 | axe on dashboard and analytics | ❌ B32 |
| 33.3 | Keyboard: logical Tab order (Notifications → Search → menu); visible focus on every stop; Enter opens links and dialogs; Tab moves through 80 sample inputs in order | ✅ |
| 33.4 | Dialog opens with focus on its first field; Escape closes and returns focus to the button that opened it | ✅ |
| 33.5 | Focus stays inside an open dialog | ❌ B24 |
| 33.6 | Skip to content | ❌ B25 |
| 33.7 | Screen reader (NVDA/VoiceOver) by ear | not done: needs a person |

### Data this pass leaves behind

Professional plan (paid in Razorpay test mode); manufacturing model **Hybrid**; CHAKAN active again; WO-2026-00002 in progress; WO-2026-00003 released; NCR-2026-00006 and -00007 open; INS-2026-00011 (LOT-2026-00008) pending with 11 results; CB-200 recipe in effect.

### Still not covered

- Control Center (/platform) operator roles and the support console from the operator side (left out on request).
- Safari on a real Mac or iPhone over HTTPS, and a screen-reader pass by a person.

---

## 7. How to repeat

Start the stack (`docker compose up --build -d --wait`), open http://localhost:3000 and Mailpit at http://localhost:8025, then follow the workflow tables above in order. Each table builds on the records the previous one created. The accounts in section 3 already hold this run's data.
