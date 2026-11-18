/**
 * How a branch ends: stop for a refusal or failure, good for a release or success, and neutral for any other way
 * round. Neutral when not said.
 */
export type BranchTone = "stop" | "good" | "neutral";

export interface FlowBranch {
  when: string;
  then: string;
  tone?: BranchTone;
}

/** A step on the main path. Branches make it a decision. */
export interface FlowStep {
  label: string;
  note?: string;
  /** Who does this step, by role, when it matters who. */
  who?: string;
  branches?: FlowBranch[];
  /** The label on the arrow to the next step, such as "Yes", when this is a decision. */
  next?: string;
}

export interface Flow {
  title: string;
  steps: FlowStep[];
}

export const OVERVIEW_FLOW: Flow = {
  title: "How material moves through ProductionOS",
  steps: [
    { label: "Purchase order", note: "Raised on an approved supplier." },
    { label: "Receipt", note: "Each line creates a lot with its own number." },
    {
      label: "Does the material require inspection?",
      next: "Yes",
      branches: [{ when: "No", then: "The lot is released straight away, and goes on to production.", tone: "good" }],
    },
    { label: "Inspection", note: "Results, a second person's approval, then a disposition." },
    {
      label: "Is the lot released?",
      next: "Accepted",
      branches: [
        { when: "Rejected or reworked", then: "The lot stays blocked, and an NCR is raised.", tone: "stop" },
        { when: "On hold", then: "The lot waits for a further decision." },
        { when: "Failed, but conditionally accepted", then: "The lot moves on with a justification, and an NCR is raised." },
      ],
    },
    { label: "Production batch", note: "Consumes released lots, then has its own inspections." },
    { label: "Dispatch", note: "Only released batches can go out." },
    { label: "Customer", note: "Every lot can now be traced to here, and a recall can reach them." },
  ],
};

/** Each topic's workflows, in the order a new user meets them. */
export const DOC_FLOWS: Record<string, Flow[]> = {
  "getting-started": [
    {
      title: "From sign-up to first inspection",
      steps: [
        { label: "Register, and confirm your email", who: "Owner" },
        { label: "Fill in the organisation's profile and logo", who: "Owner", note: "Under My account." },
        { label: "Add a facility", who: "Administrator" },
        { label: "Invite your team, each with a role", who: "Administrator" },
        { label: "Add and approve suppliers", who: "Quality manager" },
        { label: "Add materials and products", who: "Quality manager" },
        { label: "Publish an inspection template", who: "Quality manager" },
        { label: "Raise a purchase order and receive against it", who: "Purchasing" },
        { label: "Inspect the lot", who: "Inspector" },
      ],
    },
    {
      title: "Where each setting lives",
      steps: [
        { label: "Open the menu at the left" },
        {
          label: "What do you want to change?",
          branches: [
            { when: "Your name, password or emails", then: "My account." },
            { when: "Organisation profile and logo", then: "My account, for administrators." },
            { when: "Review, NCR and CAPA rules, NCR status names, support access", then: "Settings." },
            { when: "Plan, invoices and payments", then: "Subscription and billing." },
          ],
        },
        { label: "Save: the change is in the audit log" },
      ],
    },
  ],
  "users-and-roles": [
    {
      title: "Inviting someone",
      steps: [
        { label: "Choose an email and a role", who: "Administrator" },
        {
          label: "Is there a user place left on your plan?",
          next: "Yes",
          branches: [{ when: "No", then: "The invitation is refused. Pending invitations count too.", tone: "stop" }],
        },
        { label: "Invitation email goes out" },
        { label: "They set a password and join", who: "New member" },
        { label: "Optionally, limit them to certain facilities", who: "Administrator" },
      ],
    },
    {
      title: "Giving a person exactly what they need",
      steps: [
        { label: "Start from a built-in role", who: "Administrator", note: "Owner, quality manager, inspector, viewer and others." },
        {
          label: "Does a built-in role fit?",
          next: "No",
          branches: [{ when: "Yes", then: "Assign it under Users.", tone: "good" }],
        },
        { label: "Create a custom role", note: "Tick the permissions it needs, area by area." },
        { label: "Assign it to the person" },
        { label: "Their menu, records and dashboard figures follow it", note: "A figure shows only to someone who may view its records." },
      ],
    },
  ],
  facilities: [
    {
      title: "Setting up a plant",
      steps: [
        { label: "Add the facility", who: "Administrator", note: "Your plan sets how many you can have." },
        { label: "Open it from the list" },
        { label: "Add departments, production lines and shifts", note: "Each change is audited, with what was added or removed." },
        { label: "Limit people to it, if they work only there", note: "Under Users." },
      ],
    },
  ],
  suppliers: [
    {
      title: "Supplier approval",
      steps: [
        { label: "Draft", who: "Purchasing", note: "Details, contacts and category." },
        { label: "Submit for approval" },
        {
          label: "Approve the supplier?",
          who: "Quality manager",
          next: "Approve",
          branches: [{ when: "Reject, with a reason", then: "Rejected. It can be sent for approval again.", tone: "stop" }],
        },
        {
          label: "Approved",
          note: "Can be used on purchase orders.",
          branches: [{ when: "Block, with a reason", then: "Cannot be used until it is approved again.", tone: "stop" }],
        },
        { label: "Its lots, rejections and NCRs build its scorecard", note: "Under Analytics." },
      ],
    },
  ],
  "materials-and-products": [
    {
      title: "What happens to a received material",
      steps: [
        { label: "Add the material, with its unit", who: "Quality manager" },
        { label: "Add its specifications and whether it needs inspection" },
        { label: "A lot of it is received", who: "Stores" },
        {
          label: "Does it require inspection?",
          next: "Yes",
          branches: [{ when: "No", then: "The lot is released on receipt.", tone: "good" }],
        },
        { label: "The lot waits for inspection", note: "Inspectors are notified." },
      ],
    },
    {
      title: "Changing a product",
      steps: [
        { label: "Edit the product or its specifications", who: "Quality manager" },
        { label: "Saved, with only what changed in the audit log", note: "Before and after values." },
        {
          label: "Is it a change to the design?",
          branches: [{ when: "Yes", then: "Add a revision with a change note. The current revision moves on.", tone: "neutral" }],
        },
        { label: "Templates and BOMs pick it up from their next version" },
      ],
    },
  ],
  "inspection-templates": [
    {
      title: "Template versions",
      steps: [
        { label: "Draft", who: "Quality manager", note: "Add parameters, limits and sampling." },
        {
          label: "At least one parameter?",
          next: "Yes",
          branches: [{ when: "No", then: "Cannot be published.", tone: "stop" }],
        },
        { label: "Publish, with an effective date" },
        { label: "Published", note: "Can be used, and can never be changed." },
        { label: "Need a change? Create a new version", note: "The current one stays in force meanwhile." },
        { label: "Publish the new version", note: "The old one is superseded. Past inspections keep it." },
      ],
    },
    {
      title: "How many samples a lot needs",
      steps: [
        { label: "Choose the sampling on the template", who: "Quality manager" },
        {
          label: "Which sampling?",
          branches: [
            { when: "Fixed", then: "The same number of samples every time." },
            { when: "Percentage", then: "A share of the lot, rounded up." },
            { when: "Full", then: "Every unit." },
            { when: "AQL", then: "The lot's size, the AQL and the level set the samples and how many may fail." },
          ],
        },
        { label: "A lot arrives, and an inspection starts", who: "Inspector" },
        { label: "Its sample size is fixed for that inspection", note: "Under AQL, switching rules can tighten or reduce it for the next lots." },
      ],
    },
  ],
  "purchase-orders-and-receipts": [
    {
      title: "From order to lot",
      steps: [
        { label: "Draft", who: "Purchasing", note: "Can still be changed. An order needs an approved supplier." },
        {
          label: "Issue the order?",
          next: "Issue",
          branches: [{ when: "Called off", then: "Cancelled. An order with receipts is closed instead.", tone: "stop" }],
        },
        { label: "Issued", note: "Open for receiving." },
        {
          label: "Receive a delivery",
          who: "Stores",
          note: "Creates a receipt and a lot for each line.",
          branches: [{ when: "Some quantity is still due", then: "Partially received. Receive again, or close it." }],
        },
        { label: "Received", note: "Everything has arrived." },
        { label: "Closed" },
      ],
    },
  ],
  inspections: [
    {
      title: "From results to release",
      steps: [
        { label: "Pending", note: "Created from a lot or batch." },
        { label: "Enter results, sample by sample", who: "Inspector", note: "Save as you go. Each value is checked against its limits." },
        { label: "Submit for approval", who: "Inspector", note: "Results are locked and evaluated." },
        {
          label: "A second person reviews",
          who: "Reviewer",
          next: "Approve",
          branches: [{ when: "Sent back", then: "Returns to In progress for correction." }],
        },
        { label: "Approved", note: "The result is final. A reviewer cannot overturn a failure." },
        {
          label: "Record a disposition",
          who: "Quality manager",
          branches: [
            { when: "Accept (passed only)", then: "The material is released.", tone: "good" },
            { when: "Conditional acceptance", then: "Released with a justification. A failed result raises an NCR." },
            { when: "Hold", then: "Stays Approved until a further decision." },
            { when: "Rework or reject", then: "Not released, and an NCR is raised.", tone: "stop" },
          ],
        },
        { label: "Completed" },
      ],
    },
    {
      title: "Recording on the shop floor without a connection",
      steps: [
        { label: "Open the inspection while online", who: "Inspector" },
        { label: "The connection drops", note: "A notice at the top of the page says so." },
        { label: "Keep entering values, and select Save" },
        {
          label: "Could the device keep them?",
          next: "Yes",
          branches: [{ when: "No, storage is full", then: "The page says they are not saved. Stay on it until the connection is back.", tone: "stop" }],
        },
        { label: "Kept on this device, marked Not sent yet", note: "Only for you, in this organisation." },
        { label: "The connection comes back", note: "Values are sent on their own, or with Send now." },
        {
          label: "Does the server accept them?",
          next: "Yes",
          branches: [
            { when: "A newer measurement was saved meanwhile", then: "The newer one is kept, and yours is in the audit log.", tone: "neutral" },
            { when: "The inspection was submitted or closed", then: "Not accepted. Discard them from the notice.", tone: "stop" },
          ],
        },
        { label: "Saved, with the time each was measured", note: "Then submit for approval." },
      ],
    },
  ],
  ncrs: [
    {
      title: "Where an NCR comes from",
      steps: [
        { label: "Something does not conform" },
        {
          label: "Where was it found?",
          branches: [
            { when: "A failed inspection", then: "Raised by the disposition." },
            { when: "An audit finding", then: "Raised from the finding." },
            { when: "A customer complaint or a return", then: "Raised from the dispatch." },
            { when: "Production rejects", then: "Raised from the shop floor." },
            { when: "A supplier issue or a process incident", then: "Raised by hand, naming the supplier, or the line, work centre or machine." },
          ],
        },
        { label: "Open NCR, with its source", note: "It then follows one life cycle." },
      ],
    },
    {
      title: "NCR life cycle",
      steps: [
        {
          label: "Open",
          branches: [{ when: "Withdrawn", then: "Cancelled. An NCR from an inspection can only be rejected.", tone: "stop" }],
        },
        {
          label: "Assign it: Under investigation",
          who: "Quality manager",
          branches: [{ when: "Not a real non-conformance", then: "Rejected by a reviewer.", tone: "stop" }],
        },
        { label: "Record containment", who: "Assignee" },
        { label: "Record the root cause: Action required", who: "Assignee" },
        {
          label: "Critical or major?",
          next: "No",
          branches: [{ when: "Yes", then: "Raise a CAPA before it can close. The rule is set under Settings." }],
        },
        { label: "Request verification", who: "Assignee" },
        {
          label: "A different person reviews",
          who: "Reviewer",
          next: "Effective",
          branches: [{ when: "Not good enough", then: "Sent back to Action required.", tone: "stop" }],
        },
        { label: "Closed" },
      ],
    },
  ],
  capas: [
    {
      title: "CAPA life cycle",
      steps: [
        { label: "Open", who: "Quality manager", note: "With an owner and target date." },
        { label: "Root cause analysis", who: "Owner" },
        { label: "Action planning", who: "Owner", note: "Add at least one action, each with its own owner and date." },
        {
          label: "Implementation: is every action done?",
          who: "Action owners",
          next: "Yes",
          branches: [{ when: "Evidence required", then: "An action needs its evidence attached before it can be completed." }],
        },
        { label: "Effectiveness verification" },
        {
          label: "Someone other than the owner: did the fix work?",
          who: "Verifier",
          next: "Effective",
          branches: [{ when: "Not effective", then: "Back to Action planning, with a new action needed.", tone: "stop" }],
        },
        { label: "Closed", note: "A separate step, by someone other than the owner." },
      ],
    },
    {
      title: "When a CAPA runs late",
      steps: [
        { label: "A CAPA or one of its actions passes its date" },
        { label: "Its owner gets a daily reminder" },
        {
          label: "Late by the escalation days set under Settings?",
          next: "Yes",
          branches: [{ when: "Set to 0", then: "Never escalated.", tone: "neutral" }],
        },
        { label: "CAPA approvers at its plant are told, once", note: "In the app, and by email if they chose it." },
        { label: "They follow it up, or move the date with a reason", who: "CAPA approver" },
      ],
    },
  ],
  production: [
    {
      title: "Batch life cycle",
      steps: [
        { label: "In progress", who: "Production", note: "Consume released lots. In-process checks happen here." },
        { label: "Complete the batch", note: "Record the quantity made. Consumption must be recorded first." },
        { label: "Register serial numbers, if used" },
        { label: "Final inspection", who: "Inspector" },
        {
          label: "Disposition",
          who: "Quality manager",
          next: "Accept",
          branches: [
            { when: "Hold or rework", then: "On hold." },
            { when: "Reject", then: "Rejected. It cannot be dispatched.", tone: "stop" },
          ],
        },
        { label: "Released", note: "It can now be dispatched or sent to another plant." },
      ],
    },
    {
      title: "Sending stock to another plant",
      steps: [
        { label: "Choose released lots or batches, and how much of each", who: "Stores", note: "Under Stock transfers. Up to 50 lines." },
        {
          label: "Is it free to send?",
          next: "Yes",
          branches: [{ when: "Reserved, expired or not released", then: "Refused.", tone: "stop" }],
        },
        {
          label: "In transit",
          note: "The quantity has left the sending plant.",
          branches: [{ when: "Called off", then: "Cancel with a reason. The stock goes back.", tone: "stop" }],
        },
        { label: "Receive it at the other plant", who: "Stores at the receiving plant" },
        { label: "Each line is a lot or batch of its own there", note: "Numbered after its source, and traced, held and recalled with it." },
      ],
    },
  ],
  "manufacturing-setup": [
    {
      title: "Getting a definition approved",
      steps: [
        { label: "Draft", who: "Production engineer", note: "BOM, recipe or routing. Only a draft can change." },
        { label: "Submit", note: "Approvers are notified." },
        {
          label: "Approver's decision",
          who: "Approver",
          next: "Approve",
          branches: [{ when: "Send back", then: "Returns to draft with a note, and the submitter is notified.", tone: "stop" }],
        },
        { label: "Approved", note: "Takes effect on its date. The revision before it ends the day before." },
        { label: "Retired", note: "Obsolete. A newer revision, or none, is used from then on." },
      ],
    },
  ],
  "work-orders": [
    {
      title: "From plan to work order",
      steps: [
        { label: "Draft a production plan for a period", who: "Planner" },
        { label: "Add lines: product, quantity and due date" },
        { label: "Approve the plan", who: "Approver" },
        { label: "Raise a work order from each line", who: "Planner" },
      ],
    },
    {
      title: "Work order life cycle",
      steps: [
        { label: "Draft", who: "Planner", note: "Raised by hand or from a line of an approved plan." },
        {
          label: "Submitted",
          next: "Approve",
          branches: [{ when: "Sent back", then: "Draft again, with the approver's note.", tone: "stop" }],
        },
        { label: "Approved", who: "Approver" },
        { label: "Released", note: "The BOM or recipe and routing are frozen. Material can be reserved." },
        { label: "In progress", who: "Production", note: "The production batch opens. Material is issued to it." },
        {
          label: "Finished goods received",
          next: "In full",
          branches: [{ when: "Short beyond the tolerance, or the batch rejected", then: "Partially completed, with a reason." }],
        },
        { label: "Completed" },
      ],
    },
  ],
  "shop-floor": [
    {
      title: "An operation on the floor",
      steps: [
        { label: "Ready", note: "Its turn has come, and its start gates have passed." },
        { label: "Start it on a machine at its work centre", who: "Operator" },
        {
          label: "Report output",
          who: "Operator",
          note: "Good, rejected and scrap.",
          branches: [
            { when: "Rejected goods to rework", then: "A rework order, approved, then reinspected." },
            { when: "A stop", then: "Paused or blocked with a reason. A downtime record opens until it resumes.", tone: "stop" },
            { when: "Quality hold", then: "Everything pauses until the batch is reinspected.", tone: "stop" },
          ],
        },
        { label: "Completed", note: "Its completion gates must have passed. The next operation becomes ready." },
      ],
    },
  ],
  "production-completion": [
    {
      title: "From last operation to closed order",
      steps: [
        { label: "Every operation done", note: "And no rework open." },
        { label: "Receive finished goods", who: "Stores", note: "Completes the batch, and releases reservations." },
        { label: "Final inspection", who: "Inspector", note: "Decides whether the batch is released or rejected." },
        { label: "Close the order", who: "Planner", note: "Completed, or partially completed with a reason." },
        { label: "Cost and OEE", note: "Appear in Production analytics." },
      ],
    },
  ],
  "dispatches-and-returns": [
    {
      title: "Shipping, and what comes back",
      steps: [
        { label: "Create a dispatch", who: "Dispatch", note: "Released batches only, from one plant." },
        {
          label: "Did the goods physically leave?",
          next: "Yes",
          branches: [{ when: "No, it was a mistake", then: "Reverse the dispatch.", tone: "stop" }],
        },
        { label: "Confirm the shipment", note: "Managers get a daily reminder after 3 days without this." },
        {
          label: "Does the customer complain or send goods back?",
          next: "Sends back",
          branches: [
            { when: "No", then: "The dispatch is finished.", tone: "good" },
            { when: "Complains, keeping the goods", then: "Record the complaint. It raises an NCR." },
          ],
        },
        {
          label: "Record the return",
          note: "Expected, or Return received if the goods are already here.",
          branches: [{ when: "Will not happen", then: "Cancelled.", tone: "stop" }],
        },
        { label: "Pending inspection", note: "Nothing goes back into stock before this." },
        { label: "Closed", note: "Each line is accepted or rejected. A rejection raises an NCR." },
      ],
    },
  ],
  traceability: [
    {
      title: "Tracing a complaint",
      steps: [
        { label: "Start from a serial number, lot or batch", who: "Quality manager" },
        {
          label: "Which way do you need to look?",
          branches: [
            { when: "Forward from a lot", then: "Which batches used it, wherever they were sent, and which customers received them." },
            { when: "Backward from a batch", then: "Which lots and suppliers it came from." },
          ],
        },
        { label: "Check the batch's timeline", note: "Inspections, NCRs, transfers and corrections, in order." },
        { label: "If needed, start a recall" },
      ],
    },
  ],
  recalls: [
    {
      title: "Running a recall",
      steps: [
        { label: "Start the recall from one or more batches", who: "Quality manager", note: "Customers are listed from dispatch records." },
        { label: "Record what the regulator is told, if anyone", note: "Hazard class, regulator, their reference and the response days." },
        {
          label: "Contact each customer",
          branches: [{ when: "Cannot be reached", then: "Log the attempts, and record an exception." }],
        },
        {
          label: "Did they acknowledge within the response days?",
          next: "Yes",
          branches: [{ when: "No", then: "Recall approvers are told, once. Being reached is not acknowledging.", tone: "stop" }],
        },
        { label: "Return or action, then resolve the customer" },
        { label: "Request closure" },
        {
          label: "Anything blocking?",
          next: "No",
          branches: [{ when: "Customers unresolved, goods unexplained or CAPA open", then: "Closure is refused until these are dealt with.", tone: "stop" }],
        },
        {
          label: "Someone else approves",
          who: "Recall approver",
          next: "Approve",
          branches: [{ when: "Sent back", then: "The recall returns to Open.", tone: "stop" }],
        },
        { label: "Closed", note: "The recall report and the regulator's report are ready as PDFs." },
      ],
    },
  ],
  corrections: [
    {
      title: "Correcting a record",
      steps: [
        { label: "Draft", who: "Requester", note: "Say what is wrong, and what it should be." },
        {
          label: "Pending approval",
          branches: [{ when: "Requester withdraws it", then: "Cancelled.", tone: "stop" }],
        },
        {
          label: "Someone else decides",
          who: "Approver",
          next: "Approve",
          branches: [{ when: "Refused", then: "Rejected, with the record unchanged.", tone: "stop" }],
        },
        {
          label: "Was the lot inspected?",
          next: "No",
          branches: [{ when: "Yes", then: "A second approver is needed before it is applied." }],
        },
        { label: "Applied", note: "The original stays in the history." },
      ],
    },
  ],
  calibration: [
    {
      title: "Keeping an instrument in date",
      steps: [
        { label: "Register the instrument", who: "Calibration lead", note: "With a frequency and a QR label." },
        { label: "Reminder 14 days before it is due" },
        { label: "Record the calibration", who: "Calibration lead" },
        {
          label: "What was the result?",
          next: "Pass or adjusted",
          branches: [{ when: "Fail", then: "Out of service. See the next chart.", tone: "stop" }],
        },
        { label: "The next due date moves on" },
      ],
    },
    {
      title: "When a calibration fails",
      steps: [
        { label: "The instrument goes out of service" },
        { label: "A major NCR lists every inspection that used it", note: "Since its last good calibration, with the parameters it measured." },
        {
          label: "For each inspection: does the result stand?",
          who: "Quality manager",
          next: "Result stands",
          branches: [
            {
              when: "Reinspect",
              then: "The goods are held and a new inspection starts. Goods already used or dispatched are shown, with a recall report.",
              tone: "stop",
            },
          ],
        },
        { label: "Every inspection decided, with a reason" },
        { label: "The NCR can close" },
      ],
    },
  ],
  audits: [
    {
      title: "Audit life cycle",
      steps: [
        {
          label: "Planned",
          who: "Audit lead",
          branches: [{ when: "Called off", then: "Cancelled, as long as there are no findings.", tone: "stop" }],
        },
        { label: "In progress", who: "Auditor", note: "Answer every checklist question." },
        {
          label: "Any findings?",
          branches: [{ when: "Major or minor nonconformity", then: "Raise an NCR from the finding." }],
        },
        { label: "Completed" },
        { label: "Pending closure" },
        {
          label: "Someone else approves",
          who: "Approver",
          next: "Approve",
          branches: [{ when: "Sent back", then: "Returns to Completed.", tone: "stop" }],
        },
        { label: "Closed", note: "The PDF report is ready." },
      ],
    },
  ],
  analytics: [
    {
      title: "From a figure to an action",
      steps: [
        { label: "Choose a period and facility", note: "The default is the last 90 days." },
        {
          label: "May you view the records behind a figure?",
          next: "Yes",
          branches: [{ when: "No", then: "That figure or chart is left out for you.", tone: "neutral" }],
        },
        { label: "Read the key figures" },
        { label: "Open the scorecard or chart behind one" },
        { label: "Download a report to share it" },
      ],
    },
  ],
  reports: [
    {
      title: "Getting a report",
      steps: [
        { label: "Choose a report", note: "Only those whose records you may view are listed." },
        { label: "Choose dates and a facility" },
        { label: "Preview the first 100 rows" },
        {
          label: "Over 5,000 rows?",
          next: "No",
          branches: [{ when: "Yes", then: "Export every row to Excel in the background. See the next chart." }],
        },
        { label: "Download PDF or Excel", note: "Written to the audit log." },
      ],
    },
    {
      title: "Exporting a large report",
      steps: [
        { label: "Select Export every row", note: "Up to 1,000,000 rows. Three exports at a time each." },
        { label: "Queued, then running", note: "You can leave the page." },
        {
          label: "Do you still have permission for this report?",
          next: "Yes",
          branches: [{ when: "No", then: "The export fails, and nothing is written.", tone: "stop" }],
        },
        { label: "Ready: you are notified" },
        { label: "Download it from Your exports", note: "Only you can, and only for 7 days." },
      ],
    },
  ],
  notifications: [
    {
      title: "How a notification reaches you",
      steps: [
        { label: "Something happens" },
        { label: "It appears under the bell", note: "Always." },
        {
          label: "Is email on for this type?",
          next: "Yes",
          branches: [{ when: "No", then: "In-app only.", tone: "neutral" }],
        },
        { label: "An email is sent" },
      ],
    },
  ],
  billing: [
    {
      title: "Choosing or changing a plan",
      steps: [
        { label: "Choose a plan and cycle", who: "Owner" },
        {
          label: "Is your organisation within its limits?",
          next: "Yes",
          branches: [{ when: "No", then: "The change is refused until you are.", tone: "stop" }],
        },
        { label: "An invoice is raised", note: "Unused time you already paid for is taken off." },
        {
          label: "Is anything left to pay?",
          next: "Yes",
          branches: [{ when: "No, the credit covers it", then: "Settled at once. Credit beyond the price adds days.", tone: "good" }],
        },
        { label: "Pay online, or by bank transfer recorded by support", who: "Owner" },
        { label: "The plan starts" },
      ],
    },
    {
      title: "Renewal, and when time runs out",
      steps: [
        { label: "7 days before the period ends, a renewal invoice is raised", note: "Due on the day it ends." },
        {
          label: "Is it paid in time?",
          next: "No",
          branches: [{ when: "Yes", then: "The next period starts.", tone: "good" }],
        },
        { label: "Reminders 7, 3 and 1 days before" },
        { label: "Grace period", note: "Everything still works." },
        { label: "Read-only", note: "Nothing is deleted, and paying restores access." },
      ],
    },
  ],
  security: [
    {
      title: "What happens on each request",
      steps: [
        { label: "You sign in", note: "Ten failed tries from one place block sign-in from there for 15 minutes." },
        { label: "Your organisation comes from your session" },
        {
          label: "Does your role allow this?",
          next: "Yes",
          branches: [{ when: "No", then: "Refused.", tone: "stop" }],
        },
        { label: "The database only shows your organisation's rows" },
        { label: "Changes go to the audit log", note: "Edits keep only what changed, before and after." },
      ],
    },
    {
      title: "Letting support look at your account",
      steps: [
        { label: "Allow support access for a number of hours", who: "Administrator", note: "Under Settings." },
        {
          label: "May support make changes?",
          branches: [
            { when: "No", then: "Support can only read.", tone: "neutral" },
            { when: "Yes, on a 24-hour grant only", then: "Their changes show as support's in the audit log." },
          ],
        },
        { label: "Support works within the time allowed" },
        { label: "Access ends on its own, or when you revoke it", note: "You see everything support read." },
      ],
    },
  ],
  platform: [
    {
      title: "Taking a manual payment",
      steps: [
        { label: "Find the organisation", who: "Platform operator", note: "Unpaid invoices come first." },
        { label: "Raise an invoice, if there is none" },
        { label: "Record the payment, with the bank reference", note: "A reference is used once." },
        { label: "The plan starts, and the owner is told" },
      ],
    },
    {
      title: "Taking money back with a credit note",
      steps: [
        { label: "Open the paid invoice, and choose Credit note", who: "Billing operator", note: "Up to what is left of it." },
        {
          label: "How does the money go back?",
          branches: [
            { when: "By bank", then: "Made outside, recorded with its reference and date.", tone: "good" },
            { when: "Through the gateway", then: "Saved first, then sent to Cashfree." },
          ],
        },
        {
          label: "What does Cashfree say?",
          next: "Success",
          branches: [
            { when: "No answer yet", then: "Pending. Its webhook, Check refund or the refunds job finds out." },
            { when: "Failed", then: "Shown as failed, and made by bank instead.", tone: "stop" },
          ],
        },
        { label: "Refunded", note: "The organisation sees the credit note and its PDF." },
      ],
    },
    {
      title: "A payment no invoice could take",
      steps: [
        { label: "Listed under Payments waiting for review" },
        { label: "Refund it, with a reason", who: "Billing operator" },
        { label: "Refund pending", note: "Saved with its reference before Cashfree is asked." },
        {
          label: "What does Cashfree say?",
          next: "Success",
          branches: [
            { when: "No answer", then: "Looked up later. Sent again under the same reference only if Cashfree never got it." },
            { when: "Failed", then: "Back to review with the reason, to refund again.", tone: "stop" },
          ],
        },
        { label: "Refunded" },
      ],
    },
  ],
};
