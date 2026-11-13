# Audit progress

Progress on the gaps raised in *QualityOS Repository Audit and Functional Flows* (5 November 2026).

Last updated: 5 November 2026.

## Done

| PR | Change | Audit gap it closes |
|---|---|---|
| #38 | Organisation settings, with a rule that lets a one-person organisation review its own work. Every self-review needs a note and is audited | P1 Approval segregation and small tenants |
| #39 | Fixed two failing browser tests (categories, returns) | Test suite health |
| #40 | Each organisation chooses which NCR severities need a closed CAPA, and which need the CAPA verified as effective | P1 NCR and CAPA workflow was fixed, with no tenant settings |
| #41 | Dispatch of a batch under an open recall is blocked. A user can override with a reason, which is audited, and the customer joins the recall straight away | P1 Recall containment: dispatch after recall start |
| #42 | A failed calibration raises a MAJOR NCR for the inspections that used the instrument since its last good calibration. Each inspection needs a decision: *Result stands* or *Reinspect*, with a reason | P1 Calibration impact closure |
| #43 | `audit/` and the documentation PDF are no longer tracked by git | Housekeeping |

## In review

| PR | Change | Merge after |
|---|---|---|
| #44 | *Reinspect* holds the goods: a lot goes back to awaiting inspection, and a batch goes on hold, until a new inspection releases them. The calibration's NCR cannot close while any decision is pending | Ready now. Everything it depends on is merged |

## Pending

### P0: release blockers (deferred until production work starts)

Phase 11 is on hold until you ask for it.

- [ ] Production deployment: Terraform, private network, ECS, RDS, S3, Secrets Manager, TLS/WAF, monitoring
- [ ] Stable JWT signing keys with rotation. Today every backend restart signs everyone out
- [ ] Backend reachable only through the web proxy, with trusted `X-Forwarded-For` and a rate limit shared across instances
- [ ] CI running on every PR, with required checks. GitHub Actions is blocked on the account, so CI has never run
- [ ] Backups and a measured restore drill (RPO/RTO)

### P1: quality process

- [ ] **Inspection sampling and routing**: AQL (ISO 2859-1) sampling tables, automatic template choice by material, product or operation, and product binding in the UI
- [ ] **CAPA execution**: evidence and due-date reminders for each action, permission for action owners to complete their own actions, escalation, and reopening when a fix proves ineffective
- [ ] **More NCR sources**: supplier issues, customer complaints and process incidents. Inspection, audit and calibration are already covered
- [ ] **Recall, remaining**: start from several batches in the UI, link a CAPA from the UI, a regulatory report pack, and escalation when customers don't respond
- [ ] **Calibration, remaining**:
  - recall goods that were already used or dispatched;
  - start the new inspection automatically after *Reinspect*;
  - link instruments to each measurement, not to the whole inspection.
- [ ] **Transfers between plants**: a stock transfer with in-transit and received states, and dispatches split by plant
- [ ] Material receipt notifies inspectors

### P1: commercial and support

- [ ] Cashfree sandbox run: success, failure, duplicate webhook, bad signature, amount mismatch
- [ ] Refunds, credit notes and reconciliation
- [ ] GST invoice reviewed by an accountant
- [ ] Proration and auto-debit renewals
- [ ] Enforce every plan feature, or label it as not enforced. Today only NCR_CAPA, REPORTS and ADVANCED are enforced
- [ ] Customer support portal: customers raise and track tickets
- [ ] Support access to a customer's data only with their consent, for a limited time, and audited
- [ ] MFA for platform operators

### P2: experience and scale

- [ ] Push notifications instead of one-minute polling, complete email templates, tenant logo
- [ ] Reviewed Hindi and Marathi translations, and translating the app itself
- [ ] Search across NCRs, CAPAs and suppliers, server-side search in pickers (now capped at 100), and restoring archived records
- [ ] Background report exports beyond 5,000 rows, and separate permissions for totals and detail
- [ ] Audit every change to child records, with before and after values
- [ ] Offline inspection on the shop floor

## Notes

- **Recall override (#41):** every built-in role that can dispatch can also manage recalls. In practice, the override is a typed reason, not a second person's approval.
- **Known limits:** the full list stays in the known-gaps table in `docs/IMPLEMENTATION_PLAN.md`.
