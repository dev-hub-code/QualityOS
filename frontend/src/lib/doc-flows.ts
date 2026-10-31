/** A step on the main path. Branches make it a decision. */
export interface FlowStep {
  label: string;
  note?: string;
  branches?: { when: string; then: string }[];
}

export interface Flow {
  title: string;
  steps: FlowStep[];
}

export const OVERVIEW_FLOW: Flow = {
  title: "How material moves through QualityOS",
  steps: [
    { label: "Purchase order", note: "Raised on an approved supplier." },
    { label: "Receipt", note: "Each line creates a lot with its own number." },
    {
      label: "Does the material require inspection?",
      branches: [{ when: "No", then: "The lot is released straight away, and goes on to production." }],
    },
    { label: "Inspection", note: "Results, a second person's approval, then a disposition." },
    {
      label: "Is the lot released?",
      branches: [
        { when: "Rejected or reworked", then: "The lot stays blocked, and an NCR is raised." },
        { when: "On hold", then: "The lot waits for a further decision." },
        { when: "Failed, but conditionally accepted", then: "The lot moves on with a justification, and an NCR is raised." },
      ],
    },
    { label: "Production batch", note: "Consumes released lots, then has its own inspections." },
    { label: "Dispatch", note: "Only released batches can go out." },
    { label: "Customer", note: "Every lot can now be traced to here, and a recall can reach them." },
  ],
};

export const DOC_FLOWS: Record<string, Flow> = {
  "getting-started": {
    title: "From sign-up to first inspection",
    steps: [
      { label: "Register, and confirm your email" },
      { label: "Add a facility" },
      { label: "Invite your team" },
      { label: "Add and approve suppliers" },
      { label: "Add materials and products" },
      { label: "Publish an inspection template" },
      { label: "Raise a purchase order and receive against it" },
      { label: "Inspect the lot" },
    ],
  },
  "users-and-roles": {
    title: "Inviting someone",
    steps: [
      { label: "Choose an email and a role" },
      {
        label: "Is there a user place left on your plan?",
        branches: [{ when: "No", then: "The invitation is refused. Pending invitations count too." }],
      },
      { label: "Invitation email goes out" },
      { label: "They set a password and join" },
      { label: "Optionally, limit them to certain facilities" },
    ],
  },
  facilities: {
    title: "Setting up a plant",
    steps: [
      { label: "Add the facility", note: "Your plan sets how many you can have." },
      { label: "Open it from the list" },
      { label: "Add departments, production lines and shifts" },
    ],
  },
  suppliers: {
    title: "Supplier approval",
    steps: [
      { label: "Draft" },
      { label: "Pending approval" },
      {
        label: "Approve the supplier?",
        branches: [{ when: "Rejected", then: "Can be sent for approval again." }],
      },
      {
        label: "Approved",
        note: "Can be used on purchase orders.",
        branches: [{ when: "Blocked", then: "Cannot be used until it is approved again." }],
      },
    ],
  },
  "materials-and-products": {
    title: "What happens to a received material",
    steps: [
      { label: "Add the material, with its unit" },
      { label: "Set whether it requires inspection" },
      { label: "A lot of it is received" },
      {
        label: "Does it require inspection?",
        branches: [{ when: "No", then: "The lot is released on receipt." }],
      },
      { label: "The lot waits for inspection" },
    ],
  },
  "inspection-templates": {
    title: "Template versions",
    steps: [
      { label: "Draft", note: "Add parameters, limits and sampling." },
      { label: "Publish, with an effective date", note: "At least one parameter is needed." },
      { label: "Published", note: "Can be used, and can never be changed." },
      { label: "Need a change? Create a new version", note: "The current one stays in force meanwhile." },
      { label: "Publish the new version", note: "The old one is superseded. Past inspections keep it." },
    ],
  },
  "purchase-orders-and-receipts": {
    title: "From order to lot",
    steps: [
      { label: "Draft", note: "Can still be changed. An order needs an approved supplier." },
      {
        label: "Issue the order?",
        branches: [{ when: "Called off", then: "Cancelled. An order with receipts is closed instead." }],
      },
      { label: "Issued", note: "Open for receiving." },
      {
        label: "Receive a delivery",
        note: "Creates a receipt and a lot for each line.",
        branches: [{ when: "Some quantity is still due", then: "Partially received. Receive again, or close it." }],
      },
      { label: "Received", note: "Everything has arrived." },
      { label: "Closed" },
    ],
  },
  inspections: {
    title: "From results to release",
    steps: [
      { label: "Pending", note: "Created from a lot or batch." },
      { label: "In progress", note: "The inspector enters results." },
      { label: "Submitted", note: "Results are locked, and evaluated against the limits." },
      {
        label: "A second person reviews",
        branches: [{ when: "Sent back", then: "Returns to In progress for correction." }],
      },
      { label: "Approved", note: "The result is final. A reviewer cannot overturn a failure." },
      {
        label: "Record a disposition",
        branches: [
          { when: "Accept (passed only)", then: "The material is released." },
          { when: "Conditional acceptance", then: "Released with a justification. A failed result raises an NCR." },
          { when: "Hold", then: "Stays Approved until a further decision." },
          { when: "Rework or reject", then: "Not released, and an NCR is raised." },
        ],
      },
      { label: "Completed" },
    ],
  },
  ncrs: {
    title: "NCR life cycle",
    steps: [
      {
        label: "Open",
        note: "From an inspection, an audit, or by hand.",
        branches: [{ when: "Withdrawn", then: "Cancelled. An NCR from an inspection can only be rejected." }],
      },
      {
        label: "Assign it: Under investigation",
        branches: [{ when: "Not a real non-conformance", then: "Rejected by a reviewer." }],
      },
      { label: "Record containment: Containment" },
      { label: "Record the root cause: Action required" },
      { label: "Critical or major? Raise a CAPA and close it", note: "A minor NCR does not need one." },
      { label: "Request verification: Pending verification" },
      {
        label: "A different person reviews",
        branches: [{ when: "Not good enough", then: "Sent back to Action required." }],
      },
      { label: "Closed" },
    ],
  },
  capas: {
    title: "CAPA life cycle",
    steps: [
      { label: "Open", note: "With an owner and target date." },
      { label: "Root cause analysis" },
      { label: "Action planning", note: "Add at least one action." },
      { label: "Implementation", note: "Every action must be completed." },
      { label: "Effectiveness verification" },
      {
        label: "Someone other than the owner: did the fix work?",
        branches: [{ when: "Not effective", then: "Returns to Action planning." }],
      },
      { label: "Closed", note: "A separate step, by someone other than the owner." },
    ],
  },
  production: {
    title: "Batch life cycle",
    steps: [
      { label: "In progress", note: "Consume released lots. IPQC can happen here." },
      { label: "Complete the batch", note: "Record the quantity made. Consumption must be recorded first." },
      { label: "Register serial numbers, if used" },
      { label: "Final inspection (FQC)" },
      {
        label: "Disposition",
        branches: [
          { when: "Hold or rework", then: "On hold." },
          { when: "Reject", then: "Rejected. It cannot be dispatched." },
        ],
      },
      { label: "Released", note: "It can now be dispatched." },
    ],
  },
  "dispatches-and-returns": {
    title: "Shipping, and what comes back",
    steps: [
      { label: "Create a dispatch", note: "Released batches only." },
      {
        label: "Did the goods physically leave?",
        branches: [{ when: "No, it was a mistake", then: "Reverse the dispatch." }],
      },
      { label: "Confirm the shipment", note: "Managers get a daily reminder after 3 days without this." },
      { label: "Customer sends goods back?", note: "If not, the dispatch is finished." },
      {
        label: "Record the return",
        note: "Expected, or Return received if the goods are already here.",
        branches: [{ when: "Will not happen", then: "Cancelled." }],
      },
      { label: "Pending inspection", note: "Nothing goes back into stock before this." },
      { label: "Closed", note: "Each line is accepted or rejected." },
    ],
  },
  traceability: {
    title: "Tracing a complaint",
    steps: [
      { label: "Start from a serial number, lot or batch" },
      {
        label: "Which way do you need to look?",
        branches: [
          { when: "Forward from a lot", then: "Which batches used it, and which customers received them." },
          { when: "Backward from a batch", then: "Which lots and suppliers it came from." },
        ],
      },
      { label: "Check the batch's timeline", note: "Inspections, NCRs and corrections, in order." },
      { label: "If needed, start a recall" },
    ],
  },
  recalls: {
    title: "Running a recall",
    steps: [
      { label: "Start the recall from a batch", note: "Customers are listed from dispatch records." },
      {
        label: "Contact each customer",
        branches: [{ when: "Cannot be reached", then: "Log the attempts, and record an exception." }],
      },
      { label: "Customer acknowledges" },
      { label: "Return or action, then resolve the customer" },
      { label: "Request closure" },
      {
        label: "Anything blocking?",
        branches: [{ when: "Customers unresolved, goods unexplained or CAPA open", then: "Closure is refused until these are dealt with." }],
      },
      {
        label: "Someone else approves",
        branches: [{ when: "Sent back", then: "The recall returns to Open." }],
      },
      { label: "Closed", note: "The PDF report is ready." },
    ],
  },
  corrections: {
    title: "Correcting a record",
    steps: [
      { label: "Draft", note: "Say what is wrong, and what it should be." },
      {
        label: "Pending approval",
        branches: [{ when: "Requester withdraws it", then: "Cancelled." }],
      },
      {
        label: "Someone else decides",
        branches: [{ when: "Refused", then: "Rejected, with the record unchanged." }],
      },
      {
        label: "Was the lot inspected?",
        branches: [{ when: "Yes", then: "A second approver is needed before it is applied." }],
      },
      { label: "Applied", note: "The original stays in the history." },
    ],
  },
  calibration: {
    title: "Keeping an instrument in date",
    steps: [
      { label: "Register the instrument", note: "With a frequency and a QR label." },
      { label: "Reminder 14 days before it is due" },
      { label: "Record the calibration" },
      {
        label: "What was the result?",
        branches: [
          { when: "Fail", then: "Out of service, and inspections that used it are flagged." },
        ],
      },
      { label: "Pass or adjusted: the next due date moves on" },
    ],
  },
  audits: {
    title: "Audit life cycle",
    steps: [
      {
        label: "Planned",
        branches: [{ when: "Called off", then: "Cancelled, as long as there are no findings." }],
      },
      { label: "In progress", note: "Answer every checklist question." },
      {
        label: "Any findings?",
        branches: [{ when: "Major or minor nonconformity", then: "Raise an NCR from the finding." }],
      },
      { label: "Completed" },
      { label: "Pending closure" },
      {
        label: "Someone else approves",
        branches: [{ when: "Sent back", then: "Returns to Completed." }],
      },
      { label: "Closed", note: "The PDF report is ready." },
    ],
  },
  analytics: {
    title: "From a figure to an action",
    steps: [
      { label: "Choose a period and facility", note: "The default is the last 90 days." },
      { label: "Read the key figures" },
      { label: "Open the scorecard or chart behind one" },
      { label: "Download a report to share it" },
    ],
  },
  reports: {
    title: "Getting a report",
    steps: [
      { label: "Choose a report" },
      { label: "Choose dates and a facility" },
      { label: "Preview the first 100 rows" },
      {
        label: "Over 5,000 rows?",
        branches: [{ when: "Yes", then: "The report says so. Choose fewer dates." }],
      },
      { label: "Download PDF or Excel", note: "Written to the audit log." },
    ],
  },
  notifications: {
    title: "How a notification reaches you",
    steps: [
      { label: "Something happens" },
      { label: "It appears under the bell", note: "Always." },
      {
        label: "Is email on for this type?",
        branches: [{ when: "No", then: "In-app only." }],
      },
      { label: "An email is sent" },
    ],
  },
  billing: {
    title: "Plans, and when time runs out",
    steps: [
      { label: "Choose a plan and cycle" },
      {
        label: "Is your organisation within its limits?",
        branches: [{ when: "No", then: "The change is refused until you are." }],
      },
      { label: "An invoice is raised", note: "Nothing changes yet." },
      { label: "The payment is recorded", note: "The plan starts, or the time is added." },
      {
        label: "Time runs out without renewing",
        note: "Reminders go out 7, 3 and 1 days before.",
      },
      { label: "Grace period", note: "Everything still works." },
      { label: "Read-only", note: "Nothing is deleted, and paying restores access." },
    ],
  },
  security: {
    title: "What happens on each request",
    steps: [
      { label: "You sign in", note: "Ten failed tries lock the account for 15 minutes." },
      { label: "Your organisation comes from your session" },
      {
        label: "Does your role allow this?",
        branches: [{ when: "No", then: "Refused." }],
      },
      { label: "The database only shows your organisation's rows" },
      { label: "Critical actions go to the audit log" },
    ],
  },
  platform: {
    title: "Taking a manual payment",
    steps: [
      { label: "Find the organisation", note: "Unpaid invoices come first." },
      { label: "Raise an invoice, if there is none" },
      { label: "Record the payment" },
      { label: "The plan starts, and the owner is told" },
    ],
  },
};
