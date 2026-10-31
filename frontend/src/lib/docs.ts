/** One step of a worked example: who acts, what they do, and what the system does in return. */
export interface ExampleStep {
  who: string;
  action: string;
  result: string;
}

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
  example: { title: string; steps: ExampleStep[] };
}

const EVERY_PLAN = "Every plan";
const GROWTH = "Growth and above";
const PROFESSIONAL = "Professional and above";

export const DOC_PAGES: DocPage[] = [
  {
    slug: "getting-started",
    group: "Start here",
    title: "Getting started",
    summary: "Create your organisation, set up the basics, and run your first inspection.",
    menu: "Dashboard",
    roles: "Organisation Owner",
    plan: EVERY_PLAN,
    sections: [
      {
        title: "How QualityOS fits together",
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
    example: {
      title: "Acme Forgings gets started",
      steps: [
        { who: "Asha, Owner", action: "Registers Acme Forgings and confirms the email address.", result: "The organisation exists, with Asha as its owner and a trial running." },
        { who: "Asha, Owner", action: "Adds the facility Pune Plant under Facilities.", result: "Pune Plant can now be chosen on orders, receipts and batches." },
        { who: "Asha, Owner", action: "Invites Meera as Quality Manager and Ravi as Quality Inspector.", result: "Each receives an email, and joins by setting a password." },
      ],
    },
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
          ["Plant Manager", "Views everything, approves inspections, records dispositions, closes NCRs and verifies CAPAs."],
          ["Quality Manager", "Owns quality processes: templates, inspections, approvals, NCRs, CAPAs, traceability, calibration and audits."],
          ["Quality Engineer", "Configures templates, carries out inspections and investigates NCRs and CAPAs."],
          ["Quality Inspector", "Records receipts, carries out inspections and raises NCRs."],
          ["Supplier Manager", "Manages suppliers, purchase orders and receipts."],
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
    example: {
      title: "Giving an inspector the right access",
      steps: [
        { who: "Asha, Owner", action: "Under Users, invites ravi@acme.example as Quality Inspector.", result: "An invitation email goes out, and one user place is taken." },
        { who: "Ravi, Inspector", action: "Opens the link, sets a password and signs in.", result: "Ravi sees Inspections and Receipts, but cannot approve an inspection." },
        { who: "Asha, Owner", action: "Limits Ravi to Pune Plant.", result: "Ravi's traces and instruments now show Pune Plant only." },
      ],
    },
  },
  {
    slug: "facilities",
    group: "Set up",
    title: "Facilities",
    summary: "Describe your plants, with their departments, production lines and shifts.",
    menu: "Facilities",
    roles: "Organisation Admin, Plant Manager",
    plan: EVERY_PLAN,
    sections: [
      {
        title: "What a facility holds",
        terms: [
          ["Department", "A part of the plant, such as Stores."],
          ["Production line", "Where batches are made."],
          ["Shift", "A working period, such as Morning."],
        ],
      },
      {
        title: "Good to know",
        list: [
          "Your plan sets how many facilities you can have.",
          "Open a facility from the list to add its departments, lines and shifts.",
        ],
      },
    ],
    example: {
      title: "Describing Pune Plant",
      steps: [
        { who: "Asha, Owner", action: "Adds the facility Pune Plant.", result: "It can now be chosen on batches and audits." },
        { who: "Asha, Owner", action: "Opens it, and adds Line 1 and the Morning shift.", result: "The plant's structure is on record." },
      ],
    },
  },
  {
    slug: "suppliers",
    group: "Set up",
    title: "Suppliers",
    summary: "Keep an approved supplier list, so that orders only go to suppliers you trust.",
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
    example: {
      title: "Approving a steel supplier",
      steps: [
        { who: "Kiran, Supplier Manager", action: "Adds Konkan Bar Mills with code KB-01 and sends it for approval.", result: "The supplier shows as Pending approval." },
        { who: "Meera, Quality Manager", action: "Reviews it and approves.", result: "Konkan Bar Mills can now be chosen on purchase orders." },
      ],
    },
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
          ["Material", "Something you buy, with a code, a unit and specifications. A material can require inspection on receipt."],
          ["Product", "Something you make, either finished or semi-finished."],
          ["Defect category", "A label for NCRs, such as Dimensional, which groups them in the Pareto chart."],
        ],
      },
      {
        title: "Good to know",
        list: [
          "A material that requires no inspection is released as soon as it is received.",
          "Quantities consumed in production are not converted between units, so record them in the lot's own unit.",
        ],
      },
    ],
    example: {
      title: "Setting up a bar and a shaft",
      steps: [
        { who: "Dev, Quality Engineer", action: "Adds material EN8 Steel Bar, measured in kilograms, with inspection required.", result: "Every received lot of it will wait for inspection." },
        { who: "Dev, Quality Engineer", action: "Adds the finished product Drive Shaft.", result: "Production batches can now be started for it." },
      ],
    },
  },
  {
    slug: "inspection-templates",
    group: "Set up",
    title: "Inspection templates",
    summary: "Define what gets checked, the limits, and how many samples to take.",
    menu: "Templates",
    roles: "Quality Manager, Quality Engineer",
    plan: EVERY_PLAN,
    sections: [
      {
        title: "Template types",
        text: "A template has a type, such as IQC (incoming), IPQC (in-process), FQC (final) or OQC (outgoing). Supplier, packaging, visual, dimensional and functional types also exist. Incoming lots use material templates, and production batches take IPQC and FQC.",
      },
      {
        title: "Parameters",
        terms: [
          ["Data type", "Number, text, yes or no, or date."],
          ["Limits", "A minimum and maximum for a number, or the expected answer for the others."],
          ["Criticality", "Critical, major or minor."],
          ["Mandatory", "The inspection cannot be finished without it."],
          ["Per sample", "Recorded once for each sample, not once for the lot."],
          ["Evidence required", "A file must be attached."],
        ],
      },
      {
        title: "Sampling",
        terms: [
          ["Fixed", "A set number of samples, such as 5."],
          ["Percentage", "A share of the received quantity."],
          ["Full", "Every unit."],
        ],
      },
      {
        title: "Versions",
        text: "A template starts as a draft, then is published with an effective date. A published version can never be changed. To change it, create a new version, and the old one becomes superseded. Inspections already carried out stay tied to the version they used.",
      },
    ],
    example: {
      title: "A diameter check on steel bar",
      steps: [
        { who: "Meera, Quality Manager", action: "Creates IQC template Bar Check, with a critical parameter DIA from 19.9 to 20.1 mm, fixed sample of 5 and acceptance number 0.", result: "The template is a draft, so it cannot be used yet." },
        { who: "Meera, Quality Manager", action: "Publishes it, effective today.", result: "Version 1 is available to inspectors and can no longer be edited." },
        { who: "Meera, Quality Manager", action: "Later, widens the limit by creating version 2.", result: "New inspections use version 2. Past ones still show version 1 limits." },
      ],
    },
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
          ["Cancelled", "Called off. An order with receipts is closed instead."],
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
          "A lot that requires inspection cannot be used until it is released.",
          "A receipt does not notify inspectors. Lots appear in the inspection queue.",
        ],
      },
    ],
    example: {
      title: "A steel delivery arrives",
      steps: [
        { who: "Kiran, Supplier Manager", action: "Raises an order on Konkan Bar Mills for 500 kg of EN8 Steel Bar and issues it.", result: "The order is Issued and open for receiving." },
        { who: "Ravi, Inspector", action: "Receives 300 kg against it, with the supplier's batch number.", result: "GRN-2026-00001 and lot LOT-2026-00001 are created, and the order shows Partially received." },
        { who: "Dev, Quality Engineer", action: "Tries to use the lot in production.", result: "Refused: the lot has not been released for use." },
      ],
    },
  },
  {
    slug: "inspections",
    group: "Incoming material",
    title: "Inspections",
    summary: "Record results, get a second person's approval, and decide what happens to the material.",
    menu: "Inspections",
    roles: "Quality Inspector, Quality Manager, Plant Manager",
    plan: EVERY_PLAN,
    sections: [
      {
        title: "The steps",
        terms: [
          ["Pending", "Created from a lot or batch, and not started."],
          ["In progress", "Results are being entered. You can save a partial entry and come back. A reviewer can also send it back here."],
          ["Submitted", "Results are locked, and waiting for a reviewer."],
          ["Approved", "The result is final, and waiting for a disposition."],
          ["Completed", "The disposition is recorded."],
          ["Cancelled", "Called off. An inspection with results cannot be cancelled."],
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
        title: "Dispositions",
        terms: [
          ["Accept", "The material is released."],
          ["Conditional acceptance", "Released with a written justification. This is the only way failed material moves on."],
          ["Hold", "Stays waiting for a further decision."],
          ["Rework", "Not released. Raises an NCR."],
          ["Reject", "Not released. Raises an NCR, with the rejected quantity."],
        ],
      },
      {
        title: "Good to know",
        list: [
          "The reviewer must be someone other than the inspector.",
          "A changed result leaves an audit event.",
          "Failed material always gets an NCR, however it is disposed of.",
          "An instrument that is overdue or out of service cannot be recorded on an inspection.",
          "Open a completed inspection to print its report.",
        ],
      },
    ],
    example: {
      title: "A lot fails on diameter",
      steps: [
        { who: "Ravi, Inspector", action: "Starts an inspection of LOT-2026-00001 with Bar Check. INS-2026-00001 is created.", result: "Five samples are asked for." },
        { who: "Ravi, Inspector", action: "On a phone, enters 20.0, 20.0, 20.1, 20.0 and 20.4 mm, then submits.", result: "20.4 is over 20.1. DIA is critical, so the evaluation is Fail, and managers are alerted." },
        { who: "Meera, Quality Manager", action: "Reviews and approves the result, then records Reject.", result: "The lot stays blocked, and NCR-2026-00001 is raised with 300 kg rejected." },
      ],
    },
  },
  {
    slug: "ncrs",
    group: "Quality",
    title: "Non-conformances (NCRs)",
    summary: "Record what went wrong, contain it, find the cause, and close it properly.",
    menu: "NCRs, Defect categories",
    roles: "Quality Manager, Quality Engineer, Plant Manager",
    plan: GROWTH,
    sections: [
      {
        title: "Where NCRs come from",
        terms: [
          ["Inspection", "Raised automatically for failed, rejected or reworked material."],
          ["Audit", "Raised from an audit finding."],
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
          ["Pending verification", "Ready for someone to check and close, or send back."],
          ["Closed", "Finished."],
          ["Rejected", "A reviewer found it was not a real non-conformance."],
          ["Cancelled", "Withdrawn while still Open. One raised by an inspection can only be rejected."],
        ],
      },
      {
        title: "Closing rules",
        list: [
          "The person who closes an NCR must differ from the person who requested verification.",
          "A critical or major NCR needs a closed CAPA.",
          "Every linked CAPA must be closed or cancelled first.",
          "A reviewer can send an NCR back if the work is not good enough.",
        ],
      },
    ],
    example: {
      title: "Working the diameter failure",
      steps: [
        { who: "Meera, Quality Manager", action: "Assigns NCR-2026-00001 to Dev, and picks the Dimensional category.", result: "Dev is notified." },
        { who: "Dev, Quality Engineer", action: "Records containment: the 300 kg is moved to the reject area.", result: "The containment is on the record." },
        { who: "Dev, Quality Engineer", action: "Records the root cause, then raises a CAPA for it.", result: "The NCR is critical, so it cannot close until that CAPA is closed." },
        { who: "Meera, Quality Manager", action: "After the CAPA closes, closes the NCR.", result: "Closed by someone other than Dev, so the rule is met." },
      ],
    },
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
          ["Action planning", "Corrective and preventive actions are being added."],
          ["Implementation", "The actions are being carried out."],
          ["Effectiveness verification", "Someone checks that the fix worked. If it did not, the CAPA returns to Action planning."],
          ["Closed", "Finished."],
          ["Cancelled", "Withdrawn."],
        ],
      },
      {
        title: "Good to know",
        list: [
          "Record the root cause before moving on, and add at least one action.",
          "Every action must be complete before verification.",
          "Someone other than the CAPA owner must verify it.",
          "A CAPA on a critical or major NCR always needs an effectiveness check.",
          "Reminders go out 3 days before the target date, and again once it is overdue.",
          "Completing an action needs the CAPA manage permission.",
          "Evidence is attached to the CAPA, not to each action.",
        ],
      },
    ],
    example: {
      title: "Stopping oversize bar",
      steps: [
        { who: "Dev, Quality Engineer", action: "Raises a CAPA on NCR-2026-00001, with Dev as owner, due in 30 days.", result: "The CAPA is Open." },
        { who: "Dev, Quality Engineer", action: "Records the cause: the supplier's rolling tolerance is too wide. Adds a corrective action to reject the lot, and a preventive one to agree a tighter tolerance.", result: "The plan is on the record, and the CAPA moves to Implementation." },
        { who: "Dev, Quality Engineer", action: "Completes both actions, and submits for verification.", result: "The CAPA waits at Effectiveness verification." },
        { who: "Meera, Quality Manager", action: "Checks the next three lots, finds them in limits, records the fix as effective, and closes the CAPA.", result: "The CAPA is Closed, and the NCR can now close." },
      ],
    },
  },
  {
    slug: "production",
    group: "Production and dispatch",
    title: "Production batches",
    summary: "Record what went into each batch, inspect it, and release it.",
    menu: "Production",
    roles: "Quality Engineer, Quality Manager",
    plan: PROFESSIONAL,
    sections: [
      {
        title: "Status",
        terms: [
          ["In progress", "Being made, and consuming lots."],
          ["Completed", "The produced quantity is recorded, and it is ready for final inspection."],
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
          "Serial numbers are registered once the batch is complete, and there cannot be more than there are units.",
        ],
      },
    ],
    example: {
      title: "Making 100 shafts",
      steps: [
        { who: "Dev, Quality Engineer", action: "Starts PB-2026-00001 for Drive Shaft at Pune Plant, and consumes 40 kg of a released lot, LOT-2026-00002.", result: "The batch records exactly what it used." },
        { who: "Dev, Quality Engineer", action: "Completes the batch with 100 produced, and registers serial numbers SH-0001 to SH-0100.", result: "The batch is Completed." },
        { who: "Ravi, Inspector", action: "Runs the FQC inspection, and it passes. Meera approves and records Accept.", result: "The batch is Released." },
      ],
    },
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
        ],
      },
      {
        title: "Two ways to undo",
        terms: [
          ["Reverse a mistake", "For a dispatch entered wrongly, before the goods left."],
          ["Customer return", "For goods that shipped and came back. Numbered RET-2026-00001."],
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
    example: {
      title: "Shipping, and a part return",
      steps: [
        { who: "Dev, Quality Engineer", action: "Dispatches 60 shafts of PB-2026-00001 to Sahyadri Auto, as DSP-2026-00001.", result: "The batch shows 40 still available." },
        { who: "Dev, Quality Engineer", action: "Confirms the shipment, with the carrier reference.", result: "The dispatch counts as shipped." },
        { who: "Dev, Quality Engineer", action: "Records a return of 5 shafts when they arrive.", result: "RET-2026-00001 waits for inspection, and the 5 are not yet available." },
        { who: "Ravi, Inspector", action: "Inspects them, and Meera records the decision.", result: "The return closes, with the outcome on each line." },
      ],
    },
  },
  {
    slug: "traceability",
    group: "Production and dispatch",
    title: "Traceability and serial search",
    summary: "Follow a lot forward to customers, or a product back to its material.",
    menu: "Traceability, Serial search",
    roles: "Anyone with traceability view",
    plan: PROFESSIONAL,
    sections: [
      {
        title: "Searches",
        terms: [
          ["Global search", "The box at the top of the menu finds lots, batches, products and serial numbers."],
          ["Forward trace", "From a lot, which batches used it and which customers received them."],
          ["Backward trace", "From a batch, which lots and suppliers it came from."],
          ["Serial search", "Where a single unit is now. Earlier customers are on its detail page."],
        ],
      },
      {
        title: "Good to know",
        list: [
          "A batch's timeline shows inspections, NCRs and corrections in order.",
          "If serial numbers were not named on a dispatch, a trace shows the customer by batch, not by unit.",
          "Search does not cover NCRs, CAPAs or suppliers.",
        ],
      },
    ],
    example: {
      title: "A customer reports a cracked shaft",
      steps: [
        { who: "Meera, Quality Manager", action: "Enters serial SH-0042 in Serial search.", result: "It belongs to PB-2026-00001, shipped to Sahyadri Auto." },
        { who: "Meera, Quality Manager", action: "Opens the batch's backward trace.", result: "It shows LOT-2026-00002, from Konkan Bar Mills." },
        { who: "Meera, Quality Manager", action: "Runs a forward trace on that lot.", result: "This shows every other batch and customer that the same steel reached." },
      ],
    },
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
          ["Contact attempted", "An attempt was logged, by email, phone, WhatsApp or another way."],
          ["Contacted", "They were reached."],
          ["Acknowledged", "They confirmed they know."],
          ["Action required", "They need to do something."],
          ["Return in progress", "Goods are coming back."],
          ["Resolved", "Finished for this customer."],
          ["Unreachable", "Attempts failed, and this is recorded as an exception."],
        ],
      },
      {
        title: "Closing a recall",
        list: [
          "Every customer must be resolved. A customer must acknowledge before being resolved.",
          "Goods left with customers must be confirmed and explained in the notes.",
          "A linked CAPA must be closed.",
          "Someone other than the requester must approve the closure.",
          "A dispatch made after a recall starts is not blocked, but its customer joins the recall at closure.",
        ],
      },
      {
        title: "The report",
        text: "The recall report is a PDF showing the batches, customers, contact history and quantities still out. It is your own record, and sending any regulatory notice remains your responsibility.",
      },
    ],
    example: {
      title: "Recalling a batch",
      steps: [
        { who: "Meera, Quality Manager", action: "Starts RCL-2026-00001 from PB-2026-00001.", result: "Sahyadri Auto is listed from the dispatch records, with 55 shafts out." },
        { who: "Meera, Quality Manager", action: "Logs a phone call, then Sahyadri Auto' acknowledgement.", result: "The contact history is recorded with dates." },
        { who: "Dev, Quality Engineer", action: "Records the 55 shafts as a customer return, and resolves the customer.", result: "Nothing is left out, so no blockers remain." },
        { who: "Meera, Quality Manager", action: "Requests closure, and Asha approves it.", result: "The recall is Closed, and the PDF report is ready." },
      ],
    },
  },
  {
    slug: "corrections",
    group: "Production and dispatch",
    title: "Material corrections",
    summary: "Fix a wrong entry without losing the original.",
    menu: "Corrections",
    roles: "Quality Engineer requests. Quality Manager or Plant Manager approves",
    plan: PROFESSIONAL,
    sections: [
      {
        title: "What can be corrected",
        terms: [
          ["Lot", "The material, supplier, supplier's batch number or quantity."],
          ["Consumption", "Which lot a production batch really used, or how much."],
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
          "A lot's internal batch number cannot be corrected, because it is generated.",
        ],
      },
    ],
    example: {
      title: "A quantity was mistyped",
      steps: [
        { who: "Dev, Quality Engineer", action: "Finds LOT-2026-00002 was entered as 400 kg, but the delivery note says 300 kg. Raises MC-2026-00001.", result: "The correction waits for approval, and the lot is unchanged." },
        { who: "Meera, Quality Manager", action: "Approves. The lot has been inspected, so a second approver is needed.", result: "It still waits." },
        { who: "Asha, Owner", action: "Gives the second approval.", result: "The quantity becomes 300 kg, and the original stays in the lot's history." },
      ],
    },
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
          ["Fail", "The instrument goes out of service, and its due date stays."],
        ],
      },
      {
        title: "Good to know",
        list: [
          "Each instrument has a calibration frequency, and a QR label you can print.",
          "A reminder goes out 14 days before the due date, and again once overdue.",
          "An overdue instrument cannot be used on an inspection.",
          "After a failed calibration, inspections that used the instrument are flagged and people are told. No NCR is raised automatically, so review them yourself.",
          "Recording an instrument on an inspection is optional, and applies to the whole inspection.",
        ],
      },
    ],
    example: {
      title: "A caliper fails calibration",
      steps: [
        { who: "Dev, Quality Engineer", action: "Registers caliper VC-9 with a 180-day frequency, and prints its QR label.", result: "VC-9 is Active, with a due date." },
        { who: "Ravi, Inspector", action: "Records VC-9 on an inspection.", result: "The inspection shows which instrument was used." },
        { who: "Dev, Quality Engineer", action: "Records a calibration on VC-9 with result Fail.", result: "VC-9 goes out of service, and the inspections it was used on are flagged." },
      ],
    },
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
          ["Completed", "All questions are answered, and findings are recorded."],
          ["Pending closure", "Waiting for a second person."],
          ["Closed", "Finished."],
          ["Cancelled", "Called off. An audit with findings cannot be cancelled."],
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
        ],
      },
    ],
    example: {
      title: "An internal audit finds a missing label",
      steps: [
        { who: "Priya, Auditor", action: "Builds a Measurement checklist, and plans an internal audit of Pune Plant.", result: "The audit is Planned, and the lead auditor is told." },
        { who: "Priya, Auditor", action: "Answers the checklist, marking one question nonconforming.", result: "Caliper VC-9 was in use without a calibration label." },
        { who: "Priya, Auditor", action: "Records a minor nonconformity, and raises its NCR.", result: "An NCR with source Audit follows the normal closing rules." },
        { who: "Meera, Quality Manager", action: "Approves the closure Priya requested.", result: "The audit is Closed, and its PDF report is available." },
      ],
    },
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
          ["Pass rate", "Inspections passed, out of those approved in the period."],
          ["Open NCRs and CAPAs", "As they stand now, whatever the period."],
          ["Overdue CAPAs", "Open, and past the target date."],
          ["Supplier lot rejection", "Lots rejected, out of lots with a final disposition. A lot on hold is not counted yet."],
          ["Material rejection", "Quantity rejected, out of quantity decided, with no unit conversion."],
          ["Defect rate", "Failed results, out of those that passed or failed."],
          ["First pass yield", "Batches whose first decided inspection passed, out of batches first decided in the period."],
          ["Inspection turnaround", "Average time from creating an inspection to approving it."],
        ],
      },
      {
        title: "Good to know",
        list: [
          "The default period is the last 90 days, and you can choose up to two years.",
          "\"No data\" means there was nothing to measure. It is not the same as 0%.",
          "Each chart has a table view under it.",
          "A facility you cannot see returns nothing, and a manual NCR has no facility, so it is left out when a facility is chosen.",
          "Anyone with report view sees these totals, even without view permission for each kind of record.",
        ],
      },
    ],
    example: {
      title: "Finding the worst supplier",
      steps: [
        { who: "Meera, Quality Manager", action: "Opens Analytics, and sets the last quarter for Pune Plant.", result: "Supplier scorecards show Konkan Bar Mills with 1 of 4 lots rejected: 25%." },
        { who: "Meera, Quality Manager", action: "Looks at the Defect Pareto.", result: "Dimensional leads, with most of the running total." },
        { who: "Meera, Quality Manager", action: "Downloads the Supplier quality report for the review meeting.", result: "An Excel file with the same figures, and the export is audited." },
      ],
    },
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
          ["Calibration", "The register as it stands today, soonest due first. The period does not apply."],
          ["Audit", "Audits planned for the period."],
          ["Batch traceability", "Batches started in the period, with one level of lots and customers."],
        ],
      },
      {
        title: "Good to know",
        list: [
          "Preview shows the first 100 rows, and downloads hold them all.",
          "A report stops at 5,000 rows and says so. Choose fewer dates to see the rest.",
          "Every download is written to the audit log.",
          "Audits, recalls and single inspections have their own detailed reports on the record itself.",
        ],
      },
    ],
    example: {
      title: "Preparing for a customer visit",
      steps: [
        { who: "Meera, Quality Manager", action: "Chooses the Calibration report for Pune Plant.", result: "Every active instrument shows, soonest due first." },
        { who: "Meera, Quality Manager", action: "Chooses the NCR report for six months, and downloads the PDF.", result: "A landscape PDF with page numbers and repeated headings." },
      ],
    },
  },
  {
    slug: "notifications",
    group: "Insight",
    title: "Notifications",
    summary: "What you are told, and how to control it.",
    menu: "Bell icon, Settings",
    roles: "Everyone",
    plan: EVERY_PLAN,
    sections: [
      {
        title: "What you are told about",
        list: [
          "Inspections waiting for approval or disposition, sent back, or failed on a critical parameter.",
          "NCRs and CAPAs assigned to you, and CAPAs due or overdue.",
          "Instruments due or overdue, and failed calibrations.",
          "Audits scheduled for you.",
          "Dispatches not confirmed after 3 days.",
          "Subscription endings and payments, for owners.",
        ],
      },
      {
        title: "Good to know",
        list: [
          "Every notification appears in the app under the bell. The count refreshes about once a minute.",
          "In Settings you can turn off email for each type. In-app notifications stay on.",
          "Email can be sent in English, Hindi or Marathi. The app itself is in English.",
        ],
      },
    ],
    example: {
      title: "Quieter email",
      steps: [
        { who: "Meera, Quality Manager", action: "In Settings, turns off email for inspections awaiting approval.", result: "Those stop arriving by email, and still appear under the bell." },
      ],
    },
  },
  {
    slug: "billing",
    group: "Account",
    title: "Subscription and billing",
    summary: "Plans, invoices, payments, and what happens when a subscription ends.",
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
          "A different plan starts on payment, and unused time on the old plan is not refunded.",
          "Choosing again replaces any unpaid invoice.",
          "A move to a smaller plan is refused if you are over its limits.",
          "We record each payment for you, as there is no online payment yet.",
        ],
      },
      {
        title: "When a subscription ends",
        terms: [
          ["Active or trial", "Everything works. Reminders go out 7, 3 and 1 days before the end."],
          ["Grace period", "Everything still works, for a few days."],
          ["Read-only", "You can read and export, but not change anything. Nothing is deleted, and paying restores access."],
        ],
      },
      {
        title: "After a downgrade",
        text: "Records made under a larger plan stay readable, but you cannot add to them. Dashboards and reports go with the plan that includes them.",
      },
    ],
    example: {
      title: "Moving from the trial to Growth",
      steps: [
        { who: "Asha, Owner", action: "Under Billing, picks Annual and chooses Growth.", result: "BILL-2026-00001 is issued, and the trial carries on." },
        { who: "Asha, Owner", action: "Downloads the invoice PDF and pays it.", result: "Once the payment is recorded, Growth starts and the invoice shows Paid." },
      ],
    },
  },
  {
    slug: "security",
    group: "Account",
    title: "Security and your data",
    summary: "How your records are protected, and what is logged.",
    menu: "Settings",
    roles: "Everyone",
    plan: EVERY_PLAN,
    sections: [
      {
        title: "How it works",
        list: [
          "Each organisation is separated in the database itself, not only in the application.",
          "Your organisation comes from your signed-in session, never from anything a browser sends.",
          "Attachments are only reachable by someone allowed to see the record they belong to.",
          "Critical actions are written to an audit log. It is read through the API, as there is no screen for it yet.",
          "Ten failed sign-ins lock an account for 15 minutes, and a reset link unlocks it.",
          "Change your own password under Settings.",
        ],
      },
      {
        title: "Certification",
        text: "QualityOS helps you run a quality system and keep its records. It does not certify your organisation or confer regulatory approval. Whether your use of it meets a particular standard is for you and your certifier to decide.",
      },
    ],
    example: {
      title: "A locked account",
      steps: [
        { who: "Ravi, Inspector", action: "Enters the wrong password ten times.", result: "The account locks for 15 minutes." },
        { who: "Ravi, Inspector", action: "Uses Forgot password, and sets a new one from the emailed link.", result: "The account is unlocked straight away." },
      ],
    },
  },
  {
    slug: "platform",
    group: "Account",
    title: "Platform administration",
    summary: "For the people who run QualityOS itself, not for customer organisations.",
    menu: "Control Center",
    roles: "Platform administrators",
    plan: "Not part of a plan",
    sections: [
      {
        title: "What an administrator can do",
        terms: [
          ["Dashboard", "See organisations, recurring revenue, money collected, churn and trends for a chosen period."],
          ["Plans", "Create, copy, change, compare and archive plans, with a saved history of every change."],
          ["Organisations", "Search by name, email or GSTIN, filter, export, and open one organisation's details."],
          ["Raise an invoice", "Issue an invoice on an organisation's behalf."],
          ["Record a payment", "Mark an invoice as paid, which starts the plan it was raised for."],
          ["Extend", "Give an organisation more time."],
        ],
      },
      {
        title: "Good to know",
        list: [
          "The Control Center link only appears for platform administrators.",
          "Suspending an organisation stops its members signing in, but changes none of its records. Every action there needs a reason.",
          "Changing a plan that has subscribers needs a reason, and shows how many organisations it reaches.",
        ],
      },
    ],
    example: {
      title: "Recording a bank transfer",
      steps: [
        { who: "A platform administrator", action: "Finds Acme Forgings under Organisations, with BILL-2026-00001 unpaid, and records the payment.", result: "The invoice shows Paid, the plan starts, and Acme's owner is told." },
      ],
    },
  },
];

export const DOC_GROUPS = [...new Set(DOC_PAGES.map((page) => page.group))];

export function docPage(slug: string) {
  return DOC_PAGES.find((page) => page.slug === slug);
}
