# Analytics definitions

Every figure covers the chosen period, by the organisation's calendar, unless it says "now". A rate with nothing to
measure shows "No data", never 0%.

| Figure | Definition |
|---|---|
| Inspections decided, passed, failed | Inspections with a final result, approved in the period |
| Pass rate | Passed / decided |
| Open NCRs | Now: not closed, rejected or cancelled |
| NCRs closed | Closed in the period |
| Open CAPAs | Now: not closed or cancelled |
| Overdue CAPAs | Now: open, with a target date before today |
| Supplier lot rejection | Receiving lots rejected / lots with a final disposition. A lot on hold is not counted yet |
| Material rejection | Quantity in rejected lots / quantity in lots with a final disposition, as recorded with no unit conversion |
| Defect rate | Results that failed / results that passed or failed. Results awaiting review are left out |
| First pass yield | Production batches whose first decided inspection passed / batches first decided in the period. Inspections of returned goods are left out |
| Inspection turnaround | Mean time from creating an inspection to approving it |

The Pareto and the category split count NCRs raised in the period, without rejected or cancelled ones. NCR age counts
open NCRs now. Supplier scorecards show rejection and NCR counts, with no composite score.

## Scope

- An inspection, batch, instrument and audit belong to their own facility.
- An NCR belongs to the facility recorded on it, which comes from what raised it or is chosen when it is raised by hand.
  A CAPA takes the facility of its NCR, or the one chosen for it.
- A record with no facility, such as an older manual NCR, is left out for a member limited to certain facilities and
  whenever a facility is chosen.
