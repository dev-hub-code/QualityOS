# Audit progress

Progress on the gaps raised in *QualityOS Repository Audit and Functional Flows* (5 November 2026).

Last updated: 17 November 2026. Later reviews (17 November) are in `AUDIT_2026-11-17.md`,
`AUDIT_MANUFACTURING_2026-11-17.md` and `AUDIT_BACKEND_2026-11-17.md`.

## Done

| PR | Change | Audit gap it closes |
|---|---|---|
| #38 | Organisation settings, with a rule that lets a one-person organisation review its own work. Every self-review needs a note and is audited | P1 Approval segregation and small tenants |
| #39 | Fixed two failing browser tests (categories, returns) | Test suite health |
| #40 | Each organisation chooses which NCR severities need a closed CAPA, and which need the CAPA verified as effective | P1 NCR and CAPA workflow was fixed, with no tenant settings |
| #41 | Dispatch of a batch under an open recall is blocked. A user can override with a reason, which is audited, and the customer joins the recall straight away | P1 Recall containment: dispatch after recall start |
| #42 | A failed calibration raises a MAJOR NCR for the inspections that used the instrument since its last good calibration. Each inspection needs a decision: *Result stands* or *Reinspect*, with a reason | P1 Calibration impact closure |
| #43 | `audit/` and the documentation PDF are no longer tracked by git | Housekeeping |

| #44 | *Reinspect* holds the goods: a lot goes back to awaiting inspection, and a batch goes on hold, until a new inspection releases them. The calibration's NCR cannot close while any decision is pending | P1 Calibration impact closure |

Everything below marked done was merged after these, between #57 and #128.

## Pending

### P0: release blockers (deferred until production work starts)

Phase 11 is on hold until you ask for it.

- [ ] Production deployment: Terraform, private network, ECS, RDS, S3, Secrets Manager, TLS/WAF, monitoring
- [ ] Stable JWT signing keys with rotation. Today every backend restart signs everyone out
- [ ] Backend reachable only through the web proxy, with trusted `X-Forwarded-For` and a rate limit shared across instances
- [ ] CI running on every PR, with required checks. GitHub Actions is blocked on the account, so CI has never run
- [ ] Backups and a measured restore drill (RPO/RTO)

### P1: quality process

- [x] **Inspection sampling and routing**: AQL (ISO 2859-1) sampling with special levels, switching rules and double
  sampling (#103, #114, #117), and the template chosen by material, product or operation
- [ ] **CAPA execution**, remaining: an action owner without `CAPA_MANAGE` cannot complete their own action, and
  there is no escalation. Done: evidence before an action completes (#101), a reminder for each late action (#80),
  and a new action needed after an ineffective verification
- [ ] **More NCR sources**, remaining: supplier issues and process incidents as sources of their own (today they are
  raised by hand). Done: customer complaints and quality returns (#104, #113), and production rejects
- [ ] **Recall**, remaining: a regulatory report pack, and escalation when customers do not respond. Done: one recall
  for several batches, and linking its CAPA later (#57)
- [x] **Calibration follow-ups** (#132): the new inspection starts on its own after *Reinspect*; the review shows the
  batches and customers the goods already reached, with a recall report for each batch; and each measurement names the
  instrument that took it, so a failure names the parameters in doubt.
- [ ] **Transfers between plants**: a stock transfer with in-transit and received states, and dispatches split by plant
- [x] Material receipt notifies inspectors (#79)

### P1: commercial and support

- [x] Two checkouts of one invoice at the same moment share one Cashfree order, a checkout stopped halfway is carried
  on rather than repeated, and a payment without an amount or in another currency is kept for a person (#128)
- [ ] Cashfree sandbox run: success, failure, duplicate webhook, bad signature, amount mismatch
- [ ] Refunds, credit notes and reconciliation
- [ ] GST invoice reviewed by an accountant
- [ ] Proration and auto-debit renewals
- [x] Every plan feature on sale is enforced: `NCR_CAPA`, `REPORTS`, `ADVANCED` and `MANUFACTURING`. A feature added
  later in the Control Center restricts nothing until code enforces it
- [x] Customer support portal: customers raise and track their requests (#98)
- [x] Support access to a customer's data only with their consent, for a limited time, and audited (read-only, or with changes for at most 24 hours if the customer allows, never approvals, access or billing)
- [x] MFA for platform operators (V20, authenticator app)

### P2: experience and scale

- [x] Notifications pushed to the bell as they happen (#106), an email template for each (#90), and the organisation's
  logo in the app, its emails and PDFs (#105, #110)
- [x] Pickers list every record (#82), and archived records can be restored (#81)
- [ ] Background report exports beyond 5,000 rows, and separate permissions for totals and detail
- [ ] Audit every change to child records, with before and after values. Done for facility units, supplier contacts,
  material mappings and units (#84)
- [ ] Offline inspection on the shop floor
- Not planned: translations. The app is English only (#89).

## Notes

- **Recall override (#41):** every built-in role that can dispatch can also manage recalls. In practice, the override is a typed reason, not a second person's approval.
- **Known limits:** the full list stays in the known-gaps table in `docs/IMPLEMENTATION_PLAN.md`.
