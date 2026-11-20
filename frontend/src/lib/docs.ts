export interface DocSection {
  title: string;
  text?: string;
  list?: string[];
  /** Rows of a two-column table: a term and what it means. */
  terms?: [string, string][];
}

export interface DocPage {
  slug: string;
  group: string;
  title: string;
  summary: string;
  /** The menu item that opens this feature in the app. */
  menu: string;
  /** Who usually does this, by role. */
  roles: string;
  /** The lowest plan that includes it. */
  plan: string;
  sections: DocSection[];
}

const EVERY_PLAN = "Every plan";
const GROWTH = "Growth and above";
const PROFESSIONAL = "Professional and above";

export const DOC_PAGES: DocPage[] = [
  {
    slug: "getting-started",
    group: "Start here",
    title: "Getting started",
    summary:
      "Create your organisation, set up the basics, and run your first inspection.",
    menu: "Dashboard",
    roles: "Organisation Owner",
    plan: EVERY_PLAN,
    sections: [
      {
        title: "How ProductionOS fits together",
        text: "Every record links to the one before it. A purchase order leads to a receipt, a receipt creates lots, and each lot is inspected before it can be used. Failures raise NCRs, and NCRs lead to CAPAs. Released lots go into production batches, and batches go out on dispatches. That chain is what makes a trace possible.",
      },
      {
        title: "Recommended order",
        list: [
          "Register, and confirm your email address.",
          "Add a facility, then invite your team.",
          "Add suppliers, and get them approved.",
          "Add materials and products, with their units.",
          "Build and publish an inspection template.",
          "Raise a purchase order, receive against it, and inspect the lot.",
        ],
      },
      {
        title: "Your trial",
        text: "A new organisation starts on a trial of the Professional plan, so every feature on these pages is available from the first day. No card is needed.",
      },
    ],
  },
  {
    slug: "users-and-roles",
    group: "Start here",
    title: "Users and roles",
    summary: "Who can do what, and how to invite and limit people.",
    menu: "Users, Roles",
    roles: "Organisation Owner, Organisation Admin",
    plan: EVERY_PLAN,
    sections: [
      {
        title: "Built-in roles",
        terms: [
          ["Organisation Owner", "Full control, including billing."],
          ["Organisation Admin", "Everything except billing."],
          [
            "Plant Manager",
            "Views everything, approves inspections, records dispositions, closes NCRs and verifies CAPAs.",
          ],
          [
            "Quality Manager",
            "Owns quality processes: templates, inspections, approvals, NCRs, CAPAs, traceability, calibration and audits.",
          ],
          [
            "Quality Engineer",
            "Configures templates, carries out inspections and investigates NCRs and CAPAs.",
          ],
          [
            "Quality Inspector",
            "Records receipts, carries out inspections and raises NCRs.",
          ],
          [
            "Supplier Manager",
            "Manages suppliers, purchase orders and receipts.",
          ],
          ["Auditor", "Read access, plus planning and running quality audits."],
          ["Viewer", "Read-only access."],
        ],
      },
      {
        title: "Good to know",
        list: [
          "A pending invitation counts towards your plan's user limit.",
          "You cannot grant a permission you do not hold yourself.",
          "An organisation must keep at least one active owner.",
          "You can create custom roles from the permissions you hold.",
          "A member can be limited to certain facilities. They then see and change only those facilities' records, and hear only about them. An NCR or CAPA with no facility stays with unlimited members.",
        ],
      },
    ],
  },
  {
    slug: "facilities",
    group: "Set up",
    title: "Facilities",
    summary:
      "Describe your plants, with their departments, production lines and shifts.",
    menu: "Facilities",
    roles: "Organisation Admin, Plant Manager",
    plan: EVERY_PLAN,
    sections: [
      {
        title: "What a facility holds",
        terms: [
          ["Department", "A part of the plant, such as Stores."],
          ["Production line", "Where batches are made."],
          [
            "Shift",
            "A working period, such as Morning. A work centre can be set to work only some shifts.",
          ],
          [
            "Holiday",
            "A day the whole facility does not work, whatever its shifts say.",
          ],
          [
            "Weekly off",
            "Days of the week the whole facility never works, such as Sunday. They count as holidays every week.",
          ],
        ],
      },
      {
        title: "Good to know",
        list: [
          "Your plan sets how many facilities you can have.",
          "Open a facility from the list to add its departments, lines, shifts, holidays and weekly off.",
        ],
      },
    ],
  },
  {
    slug: "suppliers",
    group: "Set up",
    title: "Suppliers",
    summary:
      "Keep an approved supplier list, so that orders only go to suppliers you trust.",
    menu: "Suppliers",
    roles: "Supplier Manager, Quality Manager",
    plan: EVERY_PLAN,
    sections: [
      {
        title: "Supplier status",
        terms: [
          ["Draft", "Being entered."],
          ["Pending approval", "Waiting for a decision."],
          ["Approved", "Can be used on purchase orders."],
          ["Rejected", "Refused. It can be sent for approval again."],
          ["Blocked", "Cannot be used until it is approved again."],
        ],
      },
      {
        title: "Good to know",
        list: [
          "A purchase order can only be raised on an approved supplier.",
          "Supplier codes must be unique within your organisation.",
          "Supplier performance appears under Analytics and in the Supplier quality report.",
        ],
      },
    ],
  },
  {
    slug: "materials-and-products",
    group: "Set up",
    title: "Materials and products",
    summary: "Define what you buy and what you make.",
    menu: "Materials, Products",
    roles: "Quality Engineer, Organisation Admin",
    plan: EVERY_PLAN,
    sections: [
      {
        title: "Materials and products",
        terms: [
          [
            "Material",
            "Something you buy, with a code, a unit and specifications. A material can require inspection on receipt.",
          ],
          ["Product", "Something you make, either finished or semi-finished."],
          [
            "Defect category",
            "A label for NCRs, such as Dimensional, which groups them in the Pareto chart.",
          ],
        ],
      },
      {
        title: "Good to know",
        list: [
          "A material that requires no inspection is released as soon as it is received.",
          "Give a material a shelf life in days if its lots expire. Each lot received then expires that many days after its receipt date, unless another expiry date is entered when receiving.",
          "Material used can be entered in any unit of the same kind as its lot or batch, such as grams from a lot kept in kilograms. It is converted to the lot's unit.",
        ],
      },
    ],
  },
  {
    slug: "inspection-templates",
    group: "Set up",
    title: "Inspection templates",
    summary:
      "Define what gets checked, the limits, and how many samples to take.",
    menu: "Inspection templates",
    roles: "Quality Manager, Quality Engineer",
    plan: EVERY_PLAN,
    sections: [
      {
        title: "Template types",
        text: "A template has a type, such as IQC (incoming), IPQC (in-process), FQC (final) or OQC (outgoing). Supplier, packaging, visual, dimensional and functional types also exist. Incoming lots use material templates, and production batches take IPQC, FQC and, once released, OQC.",
      },
      {
        title: "Parameters",
        terms: [
          ["Data type", "Number, text, yes or no, or date."],
          [
            "Limits",
            "A minimum and maximum for a number, or the expected answer for the others.",
          ],
          ["Criticality", "Critical, major or minor."],
          ["Mandatory", "The inspection cannot be finished without it."],
          [
            "Per sample",
            "Recorded once for each sample, not once for the lot.",
          ],
          ["Evidence required", "A file must be attached."],
        ],
      },
      {
        title: "Sampling",
        terms: [
          ["Fixed", "A set number of samples, such as 5."],
          ["Percentage", "A share of the received quantity."],
          ["Full", "Every unit."],
          [
            "AQL",
            "ISO 2859-1 / ANSI Z1.4 single or double sampling. Choose an AQL and an inspection level: I to III, or the special levels S-1 to S-4 for costly tests. Each lot's size then sets the sample size and how many nonconforming samples it accepts. With double sampling, a lot takes a smaller first sample; when that neither accepts nor rejects it, submitting asks for a second sample of the same size, and both are counted together. Where the standard has no double plan, or the lot is too small for two samples, single sampling is used. Each supplier's lots move between normal, tightened and reduced inspection by the switching rules, shown on the template's page. A sample failing several parameters counts once, and a plan over 500 samples is refused.",
          ],
        ],
      },
      {
        title: "Versions",
        text: "A template starts as a draft, then is published with an effective date. A published version can never be changed. To change it, create a new version, and the old one becomes superseded. Inspections already carried out stay tied to the version they used. The name, and the material or product a template is for, can be changed with Edit; this changes only which items it is offered for from then on.",
      },
    ],
  },
  {
    slug: "purchase-orders-and-receipts",
    group: "Incoming material",
    title: "Purchase orders and receipts",
    summary: "Order from approved suppliers, and record what actually arrives.",
    menu: "Purchase orders, Receipts",
    roles: "Supplier Manager, Quality Inspector",
    plan: EVERY_PLAN,
    sections: [
      {
        title: "Purchase order status",
        terms: [
          ["Draft", "Can still be changed."],
          ["Issued", "Sent, and open for receiving."],
          ["Partially received", "Some quantity has arrived."],
          ["Received", "Everything has arrived."],
          ["Closed", "Finished, even if less than ordered came."],
          [
            "Cancelled",
            "Called off. An order with receipts is closed instead.",
          ],
        ],
      },
      {
        title: "Receiving",
        text: "A receipt (numbered GRN-2026-00001) records one delivery against an open order. Each line creates a lot with its own internal batch number, such as LOT-2026-00001. That number follows the material through the rest of the system.",
      },
      {
        title: "Good to know",
        list: [
          "You cannot receive more than what remains on the order line.",
          "Enter a lot's expiry date when receiving if the supplier gives one; left blank, the material's shelf life sets it. A lot already past its expiry cannot be received.",
          "An expired lot cannot be reserved, issued or used in production. It may still be used on its expiry date.",
          "A lot that requires inspection cannot be used until it is released.",
          "Lots that need inspection appear in the inspection queue, and everyone who can start inspections at that facility is notified.",
        ],
      },
    ],
  },
  {
    slug: "inspections",
    group: "Incoming material",
    title: "Inspections",
    summary:
      "Record results, get a second person's approval, and decide what happens to the material.",
    menu: "Inspections",
    roles: "Quality Inspector, Quality Manager, Plant Manager",
    plan: EVERY_PLAN,
    sections: [
      {
        title: "The steps",
        terms: [
          ["Pending", "Created from a lot or batch, and not started."],
          [
            "In progress",
            "Results are being entered. You can save a partial entry and come back. A reviewer can also send it back here.",
          ],
          ["Submitted", "Results are locked, and waiting for a reviewer."],
          ["Approved", "The result is final, and waiting for a disposition."],
          ["Completed", "The disposition is recorded."],
          [
            "Cancelled",
            "Called off. An inspection with results cannot be cancelled.",
          ],
        ],
      },
      {
        title: "How the result is worked out",
        list: [
          "Each value is checked against the template's limits as it is saved.",
          "A single failed sample on a critical parameter fails the inspection.",
          "On other parameters, failures up to the acceptance number are tolerated.",
          "A text answer with no expected value needs a reviewer to choose pass or fail.",
          "A reviewer cannot overturn a failed evaluation.",
        ],
      },
      {
        title: "Without a connection",
        list: [
          "Open the inspection while you are online. If the connection drops, keep entering values and select Save: they stay on this device, marked as not sent yet.",
          "A notice at the top of every page says when you are offline and which inspections have values waiting.",
          "When the connection is back, the values are sent on their own, each with the time it was measured. Select Send now to send them at once.",
          "If someone saved a newer measurement for the same sample meanwhile, theirs is kept. If the inspection was submitted or closed meanwhile, the values are not accepted, and you can discard them.",
          "Values stay with the person who saved them, in that organisation. Someone else signing in on the same device neither sees nor sends them, and signing out with values not sent asks first.",
          "The time kept with each value is the device's clock. Keep tablets set to the right time: a wrong clock gives a wrong measurement time, and the server also keeps when each value reached it.",
          "If the device cannot keep the values, for example with its storage full, the page says they are not saved. Stay on the page until the connection is back.",
          "Submit for approval once the values are sent.",
        ],
      },
      {
        title: "Dispositions",
        terms: [
          ["Accept", "The material is released."],
          [
            "Conditional acceptance",
            "Released with a written justification. This is the only way failed material moves on.",
          ],
          ["Hold", "Stays waiting for a further decision."],
          ["Rework", "Not released. Raises an NCR."],
          [
            "Reject",
            "Not released. Raises an NCR, with the rejected quantity.",
          ],
        ],
      },
      {
        title: "Good to know",
        list: [
          "The reviewer must be someone other than the inspector. An organisation with one member can allow self-review under Settings, with a reason each time.",
          "A changed result leaves an audit event.",
          "Failed material always gets an NCR, however it is disposed of.",
          "An instrument that is overdue or out of service cannot be recorded on an inspection.",
          "Open a completed inspection to print its report or download it as a PDF.",
        ],
      },
    ],
  },
  {
    slug: "ncrs",
    group: "Quality",
    title: "Non-conformances (NCRs)",
    summary:
      "Record what went wrong, contain it, find the cause, and close it properly.",
    menu: "NCRs, Categories",
    roles: "Quality Manager, Quality Engineer, Plant Manager",
    plan: GROWTH,
    sections: [
      {
        title: "Where NCRs come from",
        terms: [
          [
            "Inspection",
            "Raised automatically for failed, rejected or reworked material.",
          ],
          ["Audit", "Raised from an audit finding."],
          [
            "Calibration",
            "Raised automatically when an instrument fails calibration.",
          ],
          [
            "Production",
            "Raised from a shop-floor report of rejected or scrapped goods, when Manufacturing settings ask for it.",
          ],
          [
            "Customer return",
            "Raised when a return is recorded as a quality complaint. One NCR covers the whole return, as major.",
          ],
          [
            "Customer complaint",
            "Raised from a dispatch's page when a customer complains about goods they kept, such as by phone.",
          ],
          ["Manual", "Raised by hand, for anything else you notice."],
        ],
      },
      {
        title: "Status",
        terms: [
          ["Open", "Newly raised. Assigning it moves it on."],
          ["Under investigation", "Someone is working out what happened."],
          ["Containment", "Action is being taken to stop it spreading."],
          ["Action required", "Corrective action is needed."],
          [
            "Pending verification",
            "Ready for someone to check and close, or send back.",
          ],
          ["Closed", "Finished."],
          ["Rejected", "A reviewer found it was not a real non-conformance."],
          [
            "Cancelled",
            "Withdrawn while still Open. One raised by an inspection can only be rejected.",
          ],
        ],
        text: "These are the standard names. An administrator can give them your own under Settings, NCR status names, such as Quarantine for Containment. The steps and their rules stay the same.",
      },
      {
        title: "Closing rules",
        list: [
          "The person who closes an NCR must differ from the person who requested verification.",
          "By default, a critical or major NCR needs a closed CAPA. An administrator can change which NCRs need one under Settings.",
          "Every linked CAPA must be closed or cancelled first.",
          "A reviewer can send an NCR back if the work is not good enough.",
        ],
      },
    ],
  },
  {
    slug: "capas",
    group: "Quality",
    title: "Corrective and preventive actions (CAPAs)",
    summary: "Fix the cause, and prove that the fix worked.",
    menu: "CAPAs",
    roles: "Quality Manager, Quality Engineer, Plant Manager",
    plan: GROWTH,
    sections: [
      {
        title: "Status",
        terms: [
          ["Open", "Raised, with an owner and target date."],
          ["Root cause analysis", "The owner is finding the cause."],
          [
            "Action planning",
            "Corrective and preventive actions are being added.",
          ],
          ["Implementation", "The actions are being carried out."],
          [
            "Effectiveness verification",
            "Someone checks that the fix worked. If it did not, the CAPA returns to Action planning.",
          ],
          ["Closed", "Finished."],
          ["Cancelled", "Withdrawn."],
        ],
      },
      {
        title: "Good to know",
        list: [
          "Record the root cause before moving on, and add at least one action.",
          "Every action must be complete before verification.",
          "Someone other than the CAPA owner must verify it. An organisation with one member can allow self-review under Settings, with a reason each time.",
          "By default, a CAPA on a critical or major NCR needs an effectiveness check. An administrator can change this under Settings.",
          "Reminders go out 3 days before the target date, and again once it is overdue.",
          "Completing an action needs the CAPA manage permission.",
          "Evidence can be attached to the CAPA as a whole or to each action.",
          "An administrator can require evidence on each action before it is completed, under Settings. A file on the CAPA as a whole does not count.",
        ],
      },
    ],
  },
  {
    slug: "production",
    group: "Production and dispatch",
    title: "Production batches",
    summary: "Record what went into each batch, inspect it, and release it.",
    menu: "Production batches",
    roles: "Quality Engineer, Quality Manager",
    plan: PROFESSIONAL,
    sections: [
      {
        title: "Status",
        terms: [
          ["In progress", "Being made, and consuming lots."],
          [
            "Completed",
            "The produced quantity is recorded, and it is ready for final inspection.",
          ],
          ["On hold", "An inspection put it on hold or rework."],
          ["Released", "It passed final inspection, and can be dispatched."],
          ["Rejected", "It cannot be dispatched."],
        ],
      },
      {
        title: "Good to know",
        list: [
          "Only a released lot can be consumed, and only up to what remains.",
          "A batch can also consume another batch, but never itself.",
          "A batch cannot be completed until what went into it is recorded.",
          "IPQC can happen during production. FQC needs a completed batch.",
          "A released batch can take an optional OQC of what is left to ship. Accepting it keeps the batch released. Nothing ships from the batch while its OQC is open. A hold stops shipping until it is decided, and a rejection stops what is left for good.",
          "Serial numbers are registered once the batch is complete, and there cannot be more than there are units.",
        ],
      },
    ],
  },
  {
    slug: "manufacturing-setup",
    group: "Manufacturing",
    title: "Work centres and definitions",
    summary:
      "Describe where work happens and how each product is made, then get each definition approved.",
    menu: "Work centres, Machines, BOMs, Recipes, Routings, Configuration",
    roles: "Production Manager, Quality Manager",
    plan: PROFESSIONAL,
    sections: [
      {
        title: "What you set up",
        terms: [
          [
            "Work centre",
            "A place in a facility where operations run, such as a turning cell. It can carry labour, machine and overhead rates per hour.",
          ],
          [
            "Machine",
            "Equipment at a work centre. Its status is AVAILABLE, RUNNING, IDLE, MAINTENANCE, BREAKDOWN or INACTIVE, and every change is kept.",
          ],
          [
            "BOM",
            "The materials and sub-assemblies that make one or more units of a discrete product, with scrap allowances. A material line can name approved substitutes, measured in a unit of the same kind, that may stand in for it one for one.",
          ],
          [
            "Recipe",
            "A BOM for a process product: a base batch size, lines that scale or stay fixed, and process parameters.",
          ],
          [
            "Routing",
            "The numbered operations that make the product, each at a work centre with setup and run times and its own parameters.",
          ],
        ],
      },
      {
        title: "Revisions",
        list: [
          "BOMs, recipes and routings are revisioned. Each revision goes Draft, Submitted, Approved, and later Obsolete.",
          "Only a draft can be changed. A submitted revision can be sent back with a note, and returns to draft.",
          "Approval sets the date the revision takes effect. The revision it replaces ends the day before.",
          "Under the separate approver rule, the person who submitted cannot approve, except as a recorded self-review.",
          "A product cannot use itself, at any depth. The check runs when a line is added and again on approval.",
        ],
      },
      {
        title: "Good to know",
        list: [
          "Approvers are notified when a revision is submitted, and the submitter when it is sent back.",
          "Configuration sets the manufacturing model, discrete, process or both, and whether work orders need a BOM or routing.",
          "A work centre cannot be archived while any of its machines is not inactive.",
          "A product or material with an approved definition in effect cannot be archived. Retire the revision first.",
        ],
      },
    ],
  },
  {
    slug: "work-orders",
    group: "Manufacturing",
    title: "Plans and work orders",
    summary:
      "Plan what to make, raise work orders, set material aside, and check the load on each work centre.",
    menu: "Production plans, Work orders, Scheduling",
    roles: "Production Planner, Production Manager",
    plan: PROFESSIONAL,
    sections: [
      {
        title: "Work order status",
        terms: [
          [
            "Draft",
            "Being prepared. The only status in which the order can be edited.",
          ],
          [
            "Submitted",
            "Waiting for approval. It can be sent back to draft with a note.",
          ],
          ["Approved", "Agreed, but nothing is frozen yet."],
          [
            "Released",
            "The BOM or recipe and routing in effect on the planned start are frozen onto the order, as its materials and operations.",
          ],
          [
            "In progress",
            "Started. Its production batch is open, so consumption and traceability work as for any batch.",
          ],
          [
            "On hold",
            "Stopped with a reason. Resuming returns it to where it was.",
          ],
          [
            "Completed",
            "Finished goods were received and the order was closed.",
          ],
          [
            "Partially completed",
            "Closed short, or with the batch rejected, and a reason recorded.",
          ],
          [
            "Cancelled",
            "Stopped for good, with a reason. Its reservations are released.",
          ],
        ],
      },
      {
        title: "Plans and materials",
        list: [
          "A production plan covers one facility and a period, with products, quantities and due dates. Work orders are raised from the lines of an approved plan.",
          "After release, the order lists what it needs and what is short. Released lots can be reserved by hand, or reserved at once first expiring first: lots expiring soonest, then lots without an expiry, oldest first. Expired lots are never offered. A line's approved substitutes are offered after its own lots, marked as substitutes, and are only ever reserved or issued by hand; they count one for one toward the line.",
          "Issuing material records consumption on the order's batch. Reservations are used first.",
          "Scheduling shows each work centre's load against its shift capacity, and warns about late orders and overloaded days. The planner decides what to move.",
        ],
      },
      {
        title: "Good to know",
        list: [
          "Approvers are notified when an order is submitted. The submitter hears if it is sent back.",
          "People who manage work orders are reminded of an order past its planned end, and planners of a plan line past its due date with less made than planned. The reminder repeats weekly until the work is done.",
          "Rescheduling needs a reason, and is recorded.",
        ],
      },
    ],
  },
  {
    slug: "shop-floor",
    group: "Manufacturing",
    title: "Shop floor, quality and downtime",
    summary:
      "Run operations, report output, hold for quality, rework what failed, and record every stop.",
    menu: "Shop floor, Quality checkpoints, Rework orders, Downtime",
    roles: "Production Operator, Production Manager, Quality Manager",
    plan: PROFESSIONAL,
    sections: [
      {
        title: "Operation status",
        terms: [
          ["Pending", "Waiting for the operations before it."],
          ["Ready", "Its turn has come. An operator can start it."],
          [
            "In progress",
            "Running, on a machine at its work centre where one is needed.",
          ],
          [
            "Paused",
            "Stopped with a reason, such as a breakdown or a material shortage.",
          ],
          [
            "Blocked",
            "Stopped by a supervisor. Unblocking returns it to where it was.",
          ],
          [
            "Completed",
            "Done. Completing with less than came in needs a reason.",
          ],
          [
            "Cancelled",
            "Skipped by a supervisor, before anyone reported on it.",
          ],
        ],
      },
      {
        title: "Reporting and quality",
        list: [
          "Each report gives the quantity processed and its split into good, rejected and scrap. Reports add up.",
          "An operation can process only what came through good from the one before it.",
          "Reports are never edited. A supervisor reverses one with a reason.",
          "Quality checkpoints turn into gates on release. A mandatory gate stops the operation from starting or completing until its inspection passes.",
          "A quality hold pauses everything running and holds the batch until it is reinspected.",
          "Rework orders rework rejected goods on the same batch, and close on the reinspection's decision.",
        ],
      },
      {
        title: "Downtime",
        list: [
          "Pausing or blocking with a reason opens a stop on the operation and machine. Resuming ends it.",
          "Setting a machine to breakdown or maintenance opens a stop too, and supervisors are notified of a breakdown.",
          "Stops can also be entered by hand. Only planned maintenance may be entered ahead of time.",
          "Each stop can name the department responsible, chosen from the facility's departments.",
          "The summary shows minutes lost by machine, work centre, line, facility, category or department.",
        ],
      },
    ],
  },
  {
    slug: "production-completion",
    group: "Manufacturing",
    title: "Completion, cost and OEE",
    summary:
      "Receive finished goods, close the order, and see what it cost and how well the plant ran.",
    menu: "Work orders, Production analytics",
    roles: "Production Manager, Production Planner",
    plan: PROFESSIONAL,
    sections: [
      {
        title: "Finishing an order",
        list: [
          "Receiving finished goods completes the batch. Every operation must be done, every mandatory gate except final release passed, and no rework open.",
          "The quantity comes from the last operation's good output. Below the order quantity less the tolerance, a reason is needed.",
          "Material used is compared with what the output needed. A line off by more than the allowed variance needs a reason.",
          "Closing needs the receipt and, by default, the batch's final inspection decided.",
          "A completed order cannot be changed. Quantities on its batch are corrected through material corrections and returns.",
        ],
      },
      {
        title: "Figures",
        terms: [
          [
            "Cost",
            "Material from purchase prices, and labour, machine and overhead from work centre rates. A missing price is listed, never guessed.",
          ],
          ["Availability", "Running time divided by planned production time."],
          [
            "Performance",
            "Ideal cycle time for what was reported, divided by running time.",
          ],
          ["Quality", "Good units divided by units reported."],
          ["OEE", "Availability × performance × quality."],
          [
            "Not known",
            "Shown when a figure cannot be worked out, such as with no shifts set. It is never shown as zero.",
          ],
        ],
      },
    ],
  },
  {
    slug: "dispatches-and-returns",
    group: "Production and dispatch",
    title: "Dispatches and returns",
    summary: "Record what went to whom, and handle what comes back.",
    menu: "Dispatches, Returns, Customers",
    roles: "Quality Manager, Quality Engineer",
    plan: PROFESSIONAL,
    sections: [
      {
        title: "Dispatching",
        list: [
          "A dispatch (DSP-2026-00001) names a customer and the batches going out. Only released batches can be dispatched.",
          "Naming serial numbers is optional. If you do, their number must match the quantity.",
          "A dispatch only counts as shipped once someone confirms the shipment. Traces flag it until then, and managers get a daily reminder after 3 days.",
          "Nothing ships from a batch while its outgoing (OQC) inspection is open. Dispatch it once that inspection is decided.",
          "Each dispatch and each customer has a page of its own. A dispatch's page shows its batches, returns and complaints, and a customer's page lists everything shipped to them.",
        ],
      },
      {
        title: "Complaints with nothing sent back",
        text: "When a customer reports a problem with goods they kept, such as by phone, open the dispatch and choose Record complaint. Give the reference, the date, the severity and what they reported. Each complaint raises its own NCR, linked to the dispatch, and stock is not touched. Goods that come back are recorded as a return instead.",
      },
      {
        title: "Two ways to undo",
        terms: [
          [
            "Reverse a mistake",
            "For a dispatch entered wrongly, before the goods left.",
          ],
          [
            "Customer return",
            "For goods that shipped and came back. Numbered RET-2026-00001.",
          ],
        ],
      },
      {
        title: "Return status",
        terms: [
          ["Expected", "The customer said goods are coming."],
          ["Return received", "The goods have arrived."],
          ["Pending inspection", "Waiting for a final or outgoing inspection."],
          ["Closed", "Decided line by line: accepted or rejected."],
          ["Cancelled", "The return will not happen."],
        ],
      },
      {
        title: "Good to know",
        text: "Returned goods never go back into stock automatically. They need an inspection first. A customer who returned everything still shows as having received the batch, so that a recall reaches them.",
      },
    ],
  },
  {
    slug: "traceability",
    group: "Production and dispatch",
    title: "Traceability and serial search",
    summary:
      "Follow a lot forward to customers, or a product back to its material.",
    menu: "Traceability, Serial search",
    roles: "Anyone with traceability view",
    plan: PROFESSIONAL,
    sections: [
      {
        title: "Searches",
        terms: [
          [
            "Global search",
            "The box at the top of the menu finds lots, batches and serial numbers, NCRs, CAPAs, suppliers, " +
              "work orders, machines, inspections, audits, instruments, products, materials, purchase orders, " +
              "receipts, dispatches, recalls and customers. Each kind shows only to people allowed to see it.",
          ],
          [
            "Forward trace",
            "From a lot, which batches used it and which customers received them.",
          ],
          [
            "Backward trace",
            "From a batch, which lots and suppliers it came from.",
          ],
          [
            "Serial search",
            "Where a single unit is now. Earlier customers are on its detail page.",
          ],
        ],
      },
      {
        title: "Good to know",
        list: [
          "A batch's timeline shows inspections, NCRs and corrections in order.",
          "If serial numbers were not named on a dispatch, a trace shows the customer by batch, not by unit.",
          "Search does not cover dispatches or recalls. Find them on their own pages.",
        ],
      },
    ],
  },
  {
    slug: "recalls",
    group: "Production and dispatch",
    title: "Recalls",
    summary: "Reach every affected customer, and prove that you did.",
    menu: "Recalls",
    roles: "Quality Manager, Quality Engineer, Plant Manager",
    plan: PROFESSIONAL,
    sections: [
      {
        title: "Customer status",
        terms: [
          ["Pending", "Not contacted yet."],
          [
            "Contact attempted",
            "An attempt was logged, by email, phone, WhatsApp or another way.",
          ],
          ["Contacted", "They were reached."],
          ["Acknowledged", "They confirmed they know."],
          ["Action required", "They need to do something."],
          ["Return in progress", "Goods are coming back."],
          ["Resolved", "Finished for this customer."],
          [
            "Unreachable",
            "Attempts failed, and this is recorded as an exception.",
          ],
        ],
      },
      {
        title: "Closing a recall",
        list: [
          "Every customer must be resolved. A customer must acknowledge before being resolved.",
          "Goods left with customers must be confirmed and explained in the notes.",
          "A linked CAPA must be closed.",
          "Someone other than the requester must approve the closure.",
          "While a recall is open, its batches, and anything made from them, cannot be dispatched. Someone who manages recalls can override this with a reason, and the customer joins the recall at once.",
          "Batches found later can be added to an open recall, and the CAPA behind it can be linked or changed.",
        ],
      },
      {
        title: "The report",
        text: "The recall report is a PDF showing the batches, customers, contact history and quantities still out. It is your own record, and sending any regulatory notice remains your responsibility.",
      },
    ],
  },
  {
    slug: "corrections",
    group: "Production and dispatch",
    title: "Material corrections",
    summary: "Fix a wrong entry without losing the original.",
    menu: "Corrections",
    roles:
      "Quality Engineer requests. Quality Manager or Plant Manager approves",
    plan: PROFESSIONAL,
    sections: [
      {
        title: "What can be corrected",
        terms: [
          [
            "Lot",
            "The material, supplier, supplier's batch number or quantity.",
          ],
          [
            "Consumption",
            "Which lot a production batch really used, or how much.",
          ],
        ],
      },
      {
        title: "Status",
        terms: [
          ["Draft", "Being prepared."],
          ["Pending approval", "Waiting for a decision."],
          ["Applied", "Approved and carried out."],
          ["Rejected", "Refused, with the record unchanged."],
          ["Cancelled", "Withdrawn by the person who asked."],
        ],
      },
      {
        title: "Good to know",
        list: [
          "Someone other than the requester must decide.",
          "A lot that has been inspected needs two approvers.",
          "If a material has gone further than the lot, you must review and acknowledge the impact first.",
          "A correction does not trigger a new inspection. The approvers decide whether to act.",
          "A quantity can be entered in any unit of the same kind. It is converted to the lot's unit, and the request shows both.",
          "Moving an entry to a lot kept in another unit takes the same amount from it, converted to that lot's unit.",
          "A lot's internal batch number cannot be corrected, because it is generated.",
        ],
      },
    ],
  },
  {
    slug: "calibration",
    group: "Compliance",
    title: "Calibration",
    summary: "Keep instruments in date, and know what to do when one fails.",
    menu: "Calibration",
    roles: "Quality Manager, Quality Engineer",
    plan: PROFESSIONAL,
    sections: [
      {
        title: "Instrument status",
        terms: [
          ["Active", "In use."],
          ["Out of service", "Cannot be recorded on an inspection."],
          ["Retired", "Finished for good."],
        ],
      },
      {
        title: "Calibration results",
        terms: [
          ["Pass", "In tolerance, and the next due date moves on."],
          ["Adjusted", "Corrected during calibration."],
          [
            "Fail",
            "The instrument goes out of service, and its due date stays.",
          ],
        ],
      },
      {
        title: "Good to know",
        list: [
          "Each instrument has a calibration frequency, and a QR label you can print.",
          "A reminder goes out 14 days before the due date, and again once overdue.",
          "An overdue instrument cannot be used on an inspection.",
          "After a failed calibration, an NCR is raised for the inspections that used the instrument since its last good calibration. Each one needs a decision: the result stands, or the goods are inspected again.",
          "A decision to reinspect puts the goods on hold until a new inspection releases them. Goods already used or dispatched are not recalled for you.",
          "The NCR cannot close until every inspection in doubt has a decision.",
          "Recording an instrument on an inspection is optional, and applies to the whole inspection.",
        ],
      },
    ],
  },
  {
    slug: "audits",
    group: "Compliance",
    title: "Quality audits",
    summary: "Plan an audit, work a checklist, and turn findings into action.",
    menu: "Audits, Audit checklists",
    roles: "Auditor, Quality Manager",
    plan: PROFESSIONAL,
    sections: [
      {
        title: "Audit types",
        text: "Internal, supplier, process or product. A supplier audit needs a supplier, and a product audit needs a product.",
      },
      {
        title: "Status",
        terms: [
          ["Planned", "Scheduled, with a lead auditor who is told."],
          ["In progress", "The checklist is being answered."],
          [
            "Completed",
            "All questions are answered, and findings are recorded.",
          ],
          ["Pending closure", "Waiting for a second person."],
          ["Closed", "Finished."],
          [
            "Cancelled",
            "Called off. An audit with findings cannot be cancelled.",
          ],
        ],
      },
      {
        title: "Findings",
        terms: [
          ["Major nonconformity", "A serious failure. It raises an NCR."],
          ["Minor nonconformity", "A smaller failure. It raises an NCR."],
          ["Observation", "Worth noting."],
          ["Opportunity", "A suggestion for improvement."],
        ],
      },
      {
        title: "Good to know",
        list: [
          "Questions are copied into the audit, so later checklist changes leave it alone.",
          "Each answer is conforming, nonconforming or not applicable.",
          "A recorded finding cannot be edited. Handle a wrong one by cancelling its NCR.",
          "Someone else must approve closing an audit, and a PDF report is available.",
          "An audit can repeat every month, quarter, half-year, year or two years. Closing it plans the next one.",
          "The lead auditor is reminded a week before a planned audit.",
        ],
      },
    ],
  },
  {
    slug: "analytics",
    group: "Insight",
    title: "Dashboard and analytics",
    summary: "See how quality is going, and where to look next.",
    menu: "Dashboard, Analytics",
    roles: "Anyone with report view",
    plan: GROWTH,
    sections: [
      {
        title: "How the main figures work",
        terms: [
          [
            "Pass rate",
            "Inspections passed, out of those approved in the period.",
          ],
          ["Open NCRs and CAPAs", "As they stand now, whatever the period."],
          ["Overdue CAPAs", "Open, and past the target date."],
          [
            "Supplier lot rejection",
            "Lots rejected, out of lots with a final disposition. A lot on hold is not counted yet.",
          ],
          [
            "Material rejection",
            "Quantity rejected, out of quantity decided, with no unit conversion.",
          ],
          [
            "Defect rate",
            "Failed results, out of those that passed or failed.",
          ],
          [
            "First pass yield",
            "Batches whose first decided inspection passed, out of batches first decided in the period.",
          ],
          [
            "Inspection turnaround",
            "Average time from creating an inspection to approving it.",
          ],
        ],
      },
      {
        title: "Good to know",
        list: [
          "The default period is the last 90 days, and you can choose up to two years.",
          '"No data" means there was nothing to measure. It is not the same as 0%.',
          "Each chart has a table view under it.",
          "A facility you cannot see returns nothing, and a manual NCR has no facility, so it is left out when a facility is chosen.",
          "Each figure and chart shows only to someone who can also view its kind of record: inspection figures need inspection view, NCR figures NCR view, CAPA figures CAPA view, supplier figures supplier view, audits waiting audit view, and production figures work order view, with downtime also needing manufacturing view.",
        ],
      },
    ],
  },
  {
    slug: "reports",
    group: "Insight",
    title: "Reports",
    summary: "Preview, then download any of eight reports as PDF or Excel.",
    menu: "Reports",
    roles: "Anyone with report view, plus the view permission for that report",
    plan: GROWTH,
    sections: [
      {
        title: "The eight reports",
        terms: [
          ["Inspection", "Inspections decided in the period."],
          ["Supplier quality", "Lots, rejections and NCRs by supplier."],
          ["Material rejection", "Lots and quantities rejected by material."],
          ["NCR", "NCRs raised in the period, with age."],
          ["CAPA", "CAPAs opened in the period, with overdue flags."],
          [
            "Calibration",
            "The register as it stands today, soonest due first. The period does not apply.",
          ],
          ["Audit", "Audits planned for the period."],
          [
            "Batch traceability",
            "Batches started in the period, with one level of lots and customers.",
          ],
        ],
      },
      {
        title: "Good to know",
        list: [
          "Preview shows the first 100 rows, and downloads hold them all.",
          "The screen and the PDF stop at 5,000 rows and say so. Excel holds up to 100,000, for a long period in one file.",
          "Every download is written to the audit log.",
          "Audits, recalls and single inspections have their own detailed reports on the record itself.",
        ],
      },
    ],
  },
  {
    slug: "notifications",
    group: "Insight",
    title: "Notifications",
    summary: "What you are told, and how to control it.",
    menu: "Bell icon, My account",
    roles: "Everyone",
    plan: EVERY_PLAN,
    sections: [
      {
        title: "What you are told about",
        list: [
          "Inspections waiting for approval or disposition, sent back, or failed on a critical parameter.",
          "NCRs and CAPAs assigned to you, and CAPAs due or overdue.",
          "Instruments due or overdue, and failed calibrations.",
          "Audits scheduled for you, and a reminder a week before.",
          "Dispatches not confirmed after 3 days.",
          "Subscription endings and payments, for owners.",
        ],
      },
      {
        title: "Good to know",
        list: [
          "Every notification appears in the app under the bell. The count updates as soon as something new arrives.",
          "In My account you can turn off email for each type. In-app notifications stay on.",
          "The app and its email are in English.",
        ],
      },
    ],
  },
  {
    slug: "billing",
    group: "Account",
    title: "Subscription and billing",
    summary:
      "Plans, invoices, payments, and what happens when a subscription ends.",
    menu: "Billing",
    roles: "Organisation Owner",
    plan: EVERY_PLAN,
    sections: [
      {
        title: "Plans and limits",
        text: "Each plan has a user limit, a facility limit and a set of features. Current prices are on the pricing section of the home page. Enterprise is agreed with us.",
      },
      {
        title: "Changing plan or renewing",
        list: [
          "Choosing a plan raises an invoice. It takes effect only once that invoice is paid.",
          "To renew, choose your current plan. Paying early adds to the time left.",
          "A different plan starts on payment. Unused time already paid for on the old plan is taken off its price, and a credit larger than the price adds days instead.",
          "Choosing again replaces any unpaid invoice.",
          "A move to a smaller plan is refused if you are over its limits.",
          "Pay online from the invoice, or by bank transfer, which we record for you.",
          "A renewal invoice is raised 7 days before a paid period ends.",
          "Money returned to you is shown as a credit note, with its PDF.",
        ],
      },
      {
        title: "When a subscription ends",
        terms: [
          [
            "Active or trial",
            "Everything works. Reminders go out 7, 3 and 1 days before the end.",
          ],
          ["Grace period", "Everything still works, for a few days."],
          [
            "Read-only",
            "You can read and export, but not change anything. Nothing is deleted, and paying restores access.",
          ],
        ],
      },
      {
        title: "After a downgrade",
        text: "Records made under a larger plan stay readable, but you cannot add to them. Dashboards and reports go with the plan that includes them.",
      },
    ],
  },
  {
    slug: "security",
    group: "Account",
    title: "Security and your data",
    summary: "How your records are protected, and what is logged.",
    menu: "My account, Settings",
    roles: "Everyone",
    plan: EVERY_PLAN,
    sections: [
      {
        title: "How it works",
        list: [
          "Each organisation is separated in the database itself, not only in the application.",
          "Your organisation comes from your signed-in session, never from anything a browser sends.",
          "Attachments are only reachable by someone allowed to see the record they belong to.",
          "Critical actions are written to the audit log, with members' sign-in and password events from the day they joined.",
          "Ten failed sign-ins from one place block sign-in from there for 15 minutes. Places the person has signed in from before are not affected, so someone else cannot lock them out. A reset link unlocks it at once.",
          "Change your own password under My account.",
          "ProductionOS support cannot see your records unless an administrator allows it under Settings, Support access, for 24 hours, 3 days or 7 days. Support can only read, unless a 24-hour grant also allows changes, which the audit log then shows as support's. You can withdraw access at any time. Every page support opens is listed there.",
          "An administrator can upload a logo under My account. It shows in the menu, at the top of your organisation's emails and on its PDF reports. Only PNG and JPEG images up to 256 KB are taken, checked by their content.",
        ],
      },
      {
        title: "Certification",
        text: "ProductionOS helps you run a quality system and keep its records. It does not certify your organisation or confer regulatory approval. Whether your use of it meets a particular standard is for you and your certifier to decide.",
      },
    ],
  },
  {
    slug: "platform",
    group: "Account",
    title: "Platform administration",
    summary:
      "For the people who run ProductionOS itself, not for customer organisations.",
    menu: "Control Center",
    roles: "Platform administrators",
    plan: "Not part of a plan",
    sections: [
      {
        title: "What an administrator can do",
        terms: [
          [
            "Dashboard",
            "See organisations, recurring revenue, money collected, churn and trends for a chosen period.",
          ],
          [
            "Plans",
            "Create, copy, change, compare and archive plans, with a saved history of every change.",
          ],
          [
            "Organisations",
            "Search by name, email or GSTIN, filter, export, and open one organisation's details.",
          ],
          ["Raise an invoice", "Issue an invoice on an organisation's behalf."],
          [
            "Record a payment",
            "Mark an invoice as paid, which starts the plan it was raised for.",
          ],
          ["Extend", "Give an organisation more time."],
        ],
      },
      {
        title: "Good to know",
        list: [
          "The Control Center link only appears for platform administrators.",
          "Each operator has a role: Super Admin, Billing Admin, Support Admin or Read-only Auditor.",
          "Critical actions ask for your password again, then allow 10 minutes.",
          "Operators sign in with a code from an authenticator app as well as their password, and set the app up on their first visit. A Super Admin can reset it for a lost phone.",
          "Support sees account and billing facts only, and support notes are never shown to customers.",
          "Maintenance mode lets customers read their records but pauses changes.",
          "Suspending an organisation stops its members signing in, but changes none of its records. Every action there needs a reason.",
          "A plan decides what an organisation has bought, and a person's role decides what they may do. Both are checked.",
          "Changing a plan that has subscribers needs a reason, and shows how many organisations it reaches.",
        ],
      },
    ],
  },
];

export const DOC_GROUPS = [...new Set(DOC_PAGES.map((page) => page.group))];

export function docPage(slug: string) {
  return DOC_PAGES.find((page) => page.slug === slug);
}
