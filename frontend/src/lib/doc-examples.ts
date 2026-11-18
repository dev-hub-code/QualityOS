/** One step of a worked example: who acts, what they do, what the system does in return, and why. */
export interface ExampleStep {
  who: string;
  action: string;
  result: string;
  /** Why the system behaves this way, or what to watch for. */
  why: string;
}

export interface WorkedExample {
  title: string;
  /** The situation before the first step. */
  scenario: string;
  steps: ExampleStep[];
  /** What to remember from it. */
  takeaways: string[];
}

/**
 * Each topic's worked examples. They follow one made-up company, Acme Forgings, which makes drive shafts from steel
 * bar at its Pune Plant, so that a record raised in one topic turns up again in the next.
 */
export const DOC_EXAMPLES: Record<string, WorkedExample[]> = {
  "getting-started": [
    {
      title: "Acme Forgings gets started",
      scenario:
        "Acme Forgings buys EN8 steel bar from Konkan Bar Mills and turns it into drive shafts for Sahyadri Auto. Asha, the owner, wants the first delivery inspected in ProductionOS by the end of the week.",
      steps: [
        {
          who: "Asha, Owner",
          action: "Registers Acme Forgings and confirms the email address.",
          result: "The organisation exists, with Asha as its owner and a Professional trial running.",
          why: "The trial starts on the largest plan, so every feature in these pages can be tried before choosing what to pay for.",
        },
        {
          who: "Asha, Owner",
          action: "Adds the facility Pune Plant under Facilities.",
          result: "Pune Plant can now be chosen on orders, receipts, batches and audits.",
          why: "Almost every record belongs to a facility. Adding it first means nothing has to be moved later.",
        },
        {
          who: "Asha, Owner",
          action: "Invites Meera as Quality Manager, Ravi as Quality Inspector and Kiran as Supplier Manager.",
          result: "Each receives an email, and joins by setting a password.",
          why: "Inspections and NCRs need a second person to approve them. With one member, those rules can only be met by a recorded self-review.",
        },
        {
          who: "Kiran and Meera",
          action: "Kiran adds Konkan Bar Mills, and Meera approves it. Meera adds EN8 Steel Bar and publishes the Bar Check template.",
          result: "An order can be raised, and a lot of the bar will have a template to be inspected against.",
          why: "Each record depends on the one before: no order without an approved supplier, and no inspection without a published template.",
        },
        {
          who: "Kiran, then Ravi",
          action: "Kiran raises and issues an order for 500 kg. When 300 kg arrives, Ravi receives it and inspects the lot.",
          result: "The lot is released or blocked by the inspection's outcome, and the chain from supplier to lot is on record.",
          why: "This chain is what makes a trace possible later: every shaft made from this bar will lead back to this delivery.",
        },
      ],
      takeaways: [
        "Set things up in order: facility, people, suppliers, materials, templates, then orders.",
        "Invite a second person early, because approvals need someone other than the person who did the work.",
        "Each later topic in this help continues the Acme Forgings story.",
      ],
    },
  ],
  "users-and-roles": [
    {
      title: "Giving an inspector the right access",
      scenario: "Ravi joins Acme Forgings to inspect incoming steel at Pune Plant only. He should record results, but not approve them.",
      steps: [
        {
          who: "Asha, Owner",
          action: "Under Users, invites ravi@acme.example as Quality Inspector.",
          result: "An invitation email goes out, and one user place is taken.",
          why: "A pending invitation counts towards the plan's user limit, so a plan cannot be oversubscribed by inviting many people at once.",
        },
        {
          who: "Ravi, Inspector",
          action: "Opens the link, sets a password and signs in.",
          result: "Ravi sees Inspections and Receipts, but has no Approve button on an inspection.",
          why: "The menu and buttons follow the role's permissions. The server checks them again on every request, so a hidden button is not the only protection.",
        },
        {
          who: "Asha, Owner",
          action: "Limits Ravi to Pune Plant.",
          result: "Ravi's lists, traces and instruments show Pune Plant only, and he hears only about it.",
          why: "Facility limits are for people who work at one site. An NCR or CAPA with no facility stays with unlimited members, so nothing goes unseen.",
        },
      ],
      takeaways: [
        "Pick the built-in role closest to the job, then limit by facility if needed.",
        "Pending invitations use a user place until they are accepted or withdrawn.",
      ],
    },
    {
      title: "A custom role for the stores supervisor",
      scenario: "Acme's stores supervisor, Nikhil, receives deliveries and needs to see suppliers, but should not inspect. No built-in role fits exactly.",
      steps: [
        {
          who: "Asha, Owner",
          action: "Under Roles, creates Stores Supervisor with supplier view, purchase view and receipt permissions.",
          result: "The role is saved and can be assigned.",
          why: "You can only grant permissions you hold yourself, so a custom role can never give more than its creator has.",
        },
        {
          who: "Asha, Owner",
          action: "Invites Nikhil with the Stores Supervisor role.",
          result: "Nikhil can record receipts, and the inspection menu does not appear for him.",
          why: "Lots he receives still wait for inspection, so receiving and inspecting stay with different people.",
        },
        {
          who: "Nikhil, Stores Supervisor",
          action: "Opens the dashboard.",
          result: "Only figures about records he may view appear. Inspection and NCR figures are left out.",
          why: "A dashboard figure is a summary of records. Showing it to someone who cannot open those records would reveal them indirectly.",
        },
      ],
      takeaways: [
        "Custom roles combine permissions area by area.",
        "The dashboard, reports and search all follow the same permissions as the records themselves.",
      ],
    },
  ],
  facilities: [
    {
      title: "Describing Pune Plant",
      scenario: "Before planning any production, Acme wants Pune Plant's structure on record, so that capacity and downtime can be measured.",
      steps: [
        {
          who: "Asha, Owner",
          action: "Adds the facility Pune Plant.",
          result: "It can now be chosen on orders, batches and audits.",
          why: "Your plan sets how many facilities you can have. A warning goes to owners as the limit gets close.",
        },
        {
          who: "Asha, Owner",
          action: "Opens it, and adds the departments Stores and Machining, Line 1, and the Morning and Evening shifts.",
          result: "The plant's structure is on record, and each change is in the audit log.",
          why: "Shifts set how many hours a work centre can work in a day. Scheduling and OEE use them to measure load and availability.",
        },
        {
          who: "Asha, Owner",
          action: "Adds 15 August as a holiday.",
          result: "No work is planned on that day, whatever the shifts say.",
          why: "Without the holiday, scheduling would show capacity on a day nobody works, and late orders would only show up afterwards.",
        },
      ],
      takeaways: [
        "Add shifts before using scheduling or OEE. Without them, those figures show as Not known rather than a misleading zero.",
        "Departments are also used to say who was responsible for a stop.",
      ],
    },
  ],
  suppliers: [
    {
      title: "Approving a steel supplier",
      scenario: "Kiran wants to buy bar from a new mill, Konkan Bar Mills. Acme only orders from suppliers its quality team has approved.",
      steps: [
        {
          who: "Kiran, Supplier Manager",
          action: "Adds Konkan Bar Mills with code KB-01, its contacts and category, and sends it for approval.",
          result: "The supplier shows as Pending approval.",
          why: "Codes are unique within your organisation, so KB-01 always means the same supplier on orders and reports.",
        },
        {
          who: "Meera, Quality Manager",
          action: "Reviews the mill's documents and approves it.",
          result: "Konkan Bar Mills can now be chosen on purchase orders.",
          why: "The person who buys and the person who approves are different, so purchasing cannot add a supplier on its own.",
        },
        {
          who: "Meera, Quality Manager",
          action: "Months later, after a run of rejected lots, blocks the supplier with a reason.",
          result: "No new order can be raised on it. Open orders and past records are unchanged.",
          why: "Blocking stops new business without rewriting history. Approving it again lifts the block.",
        },
      ],
      takeaways: [
        "Draft, pending approval, approved: only approved suppliers can take orders.",
        "Supplier scorecards under Analytics show lots received, rejected and the NCRs against each supplier.",
      ],
    },
  ],
  "materials-and-products": [
    {
      title: "Setting up a bar and a shaft",
      scenario: "Dev needs the steel bar Acme buys and the shaft it makes on record before anything can be received or produced.",
      steps: [
        {
          who: "Dev, Quality Engineer",
          action: "Adds material EN8 Steel Bar, measured in kilograms, with inspection required and a shelf life of 365 days.",
          result: "Every received lot of it waits for inspection, and expires a year after receipt unless another date is given.",
          why: "Inspection required is what holds a lot back from production. A material without it is released the moment it is received.",
        },
        {
          who: "Dev, Quality Engineer",
          action: "Adds specifications to the bar: diameter 20 mm, grade EN8.",
          result: "The specifications show on the material, and the edit is audited with only what changed.",
          why: "The audit log keeps before and after values of each field changed, so a later question about who changed a limit has an answer.",
        },
        {
          who: "Dev, Quality Engineer",
          action: "Adds the finished product Drive Shaft.",
          result: "BOMs, routings and production batches can now be created for it.",
          why: "A product is what you make, and a material is what you buy. Production batches are for products, and lots are for materials.",
        },
        {
          who: "Arjun, Production Manager",
          action: "Records 2,500 g of bar used on a batch whose lot is kept in kilograms.",
          result: "2.5 kg is taken from the lot.",
          why: "Any unit of the same kind is accepted and converted to the lot's unit, so people can use whatever unit their scale shows.",
        },
      ],
      takeaways: [
        "Decide on inspection required and shelf life when adding a material: they decide what happens to every lot received.",
        "Units of the same kind convert automatically. Different kinds, such as kilograms and pieces, never do.",
      ],
    },
  ],
  "inspection-templates": [
    {
      title: "A diameter check on steel bar",
      scenario: "Meera wants every lot of EN8 bar checked for diameter before use. Oversize bar jams the lathes, so one bad sample should fail the lot.",
      steps: [
        {
          who: "Meera, Quality Manager",
          action: "Creates IQC template Bar Check for EN8 Steel Bar, with a critical number parameter DIA from 19.9 to 20.1 mm, per sample, and a fixed sample of 5.",
          result: "The template is a draft, so it cannot be used yet.",
          why: "Critical means a single failed sample fails the whole inspection, whatever the acceptance number says.",
        },
        {
          who: "Meera, Quality Manager",
          action: "Publishes it, effective today.",
          result: "Version 1 is offered to inspectors for EN8 lots and can no longer be edited.",
          why: "A published version is fixed so that every inspection carried out under it was judged by the same limits.",
        },
        {
          who: "Meera, Quality Manager",
          action: "After agreeing a new tolerance with the mill, creates version 2 with DIA from 19.8 to 20.1 mm and publishes it.",
          result: "New inspections use version 2. Past ones still show version 1 and its limits.",
          why: "Changing limits in place would make old results look as if they were judged differently. Versions keep the history honest.",
        },
      ],
      takeaways: [
        "Draft, publish, and create a new version to change: never edit what is in use.",
        "Use critical for anything where one bad sample is unacceptable.",
      ],
    },
    {
      title: "Sampling a large delivery with AQL",
      scenario: "Acme starts buying 1,200 machined collars per delivery. Checking every one is too slow, so Meera wants a sampling plan from the standard tables.",
      steps: [
        {
          who: "Meera, Quality Manager",
          action: "Sets the Collar Check template to AQL sampling, single, inspection level II, AQL 1.0.",
          result: "The template's page shows the plan it will use for each lot size.",
          why: "AQL sampling takes its sample size and accept and reject numbers from ISO 2859-1, so the plan can be explained to a customer or auditor.",
        },
        {
          who: "Ravi, Inspector",
          action: "Starts an inspection of a lot of 1,200 collars.",
          result: "80 samples are asked for. Up to 2 nonconforming samples are accepted, and 3 reject the lot.",
          why: "The lot size picks the code letter, J for 501 to 1,200 at level II, and the AQL picks the numbers for that letter.",
        },
        {
          who: "Ravi, Inspector",
          action: "Finds 3 collars out of tolerance, one of them failing two parameters.",
          result: "That collar counts once, the lot has 3 nonconforming samples, and it fails.",
          why: "AQL counts nonconforming units, not individual failures.",
        },
        {
          who: "Meera, Quality Manager",
          action: "After a second rejected lot from the same supplier, opens the template.",
          result: "The supplier's lots are now on tightened inspection, with stricter numbers.",
          why: "The switching rules move a supplier between normal, tightened and reduced inspection based on its recent lots, so a weak supplier gets more scrutiny.",
        },
      ],
      takeaways: [
        "Choose AQL when lots are large and a standard plan is wanted.",
        "Each supplier has its own switching state on the template.",
      ],
    },
  ],
  "purchase-orders-and-receipts": [
    {
      title: "A steel delivery arrives",
      scenario: "Acme needs 500 kg of EN8 bar. Konkan Bar Mills is approved, and delivers in two loads.",
      steps: [
        {
          who: "Kiran, Supplier Manager",
          action: "Raises an order on Konkan Bar Mills for 500 kg of EN8 Steel Bar and issues it.",
          result: "The order is Issued and open for receiving.",
          why: "Only an issued order can be received against. A draft can still change, so goods cannot arrive against it.",
        },
        {
          who: "Ravi, Inspector",
          action: "Receives 300 kg against it, with the supplier's batch number H-2231.",
          result: "GRN-2026-00001 and lot LOT-2026-00001 are created, the order shows Partially received, and inspectors at Pune Plant are notified.",
          why: "Every lot gets its own internal number. The supplier's batch number is kept too, so a problem can be raised with the mill in its own terms.",
        },
        {
          who: "Dev, Quality Engineer",
          action: "Tries to use the lot in production.",
          result: "Refused: the lot has not been released for use.",
          why: "EN8 requires inspection, so the lot is blocked until an inspection releases it.",
        },
        {
          who: "Kiran, Supplier Manager",
          action: "The mill says the last 200 kg is no longer available. Kiran closes the order.",
          result: "The order is Closed, with 300 kg received.",
          why: "An order with receipts is closed, not cancelled, so the goods that did arrive stay linked to it.",
        },
      ],
      takeaways: [
        "One receipt per delivery, one lot per line.",
        "You cannot receive more than remains on a line, or a lot already past its expiry.",
      ],
    },
  ],
  inspections: [
    {
      title: "A lot fails on diameter",
      scenario: "LOT-2026-00001, 300 kg of EN8 bar, is waiting for inspection with the Bar Check template: diameter 19.9 to 20.1 mm, critical, 5 samples.",
      steps: [
        {
          who: "Ravi, Inspector",
          action: "Starts an inspection of LOT-2026-00001 with Bar Check. INS-2026-00001 is created.",
          result: "Five samples are asked for.",
          why: "The template's sampling decides how many samples are needed, so the number does not depend on who is inspecting.",
        },
        {
          who: "Ravi, Inspector",
          action: "On a phone, enters 20.0, 20.0, 20.1, 20.0 and 20.4 mm, then submits.",
          result: "20.4 is over 20.1. DIA is critical, so the evaluation is Fail, and managers are alerted.",
          why: "Each value is checked as it is saved, and one failed sample on a critical parameter fails the inspection.",
        },
        {
          who: "Meera, Quality Manager",
          action: "Reviews the results and approves them.",
          result: "The result is final, and waits for a disposition.",
          why: "Approval confirms the results were recorded properly. A reviewer cannot turn a failure into a pass.",
        },
        {
          who: "Meera, Quality Manager",
          action: "Records Reject.",
          result: "The lot stays blocked, and NCR-2026-00001 is raised with 300 kg rejected.",
          why: "Failed material always gets an NCR, so the failure is investigated as well as stopped.",
        },
      ],
      takeaways: [
        "Inspect, submit, a second person approves, then a disposition decides what happens to the material.",
        "Only Accept or Conditional acceptance release material. A conditional acceptance of failed material still raises an NCR.",
      ],
    },
    {
      title: "Inspecting in a basement store with no signal",
      scenario: "Ravi inspects a lot in the basement store, where the tablet loses its connection halfway through.",
      steps: [
        {
          who: "Ravi, Inspector",
          action: "Opens INS-2026-00007 upstairs, then goes down to the lot.",
          result: "The inspection is on screen.",
          why: "The inspection must be opened while online, so the tablet has its template and samples.",
        },
        {
          who: "Ravi, Inspector",
          action: "Loses signal, enters the remaining values, and selects Save.",
          result: "The values are kept on the tablet, marked Not sent yet, and a notice at the top of the page says so.",
          why: "Each value is kept with the time it was measured, taken from the tablet's clock. Keep tablets set to the right time.",
        },
        {
          who: "Ravi, Inspector",
          action: "Walks back upstairs.",
          result: "The values are sent on their own, and the notice clears.",
          why: "Nothing needs to be done to send them. Send now sends them at once if Ravi does not want to wait.",
        },
        {
          who: "Dev, Quality Engineer",
          action: "Had re-measured one of the same samples online while Ravi was downstairs.",
          result: "Dev's later measurement is kept. Ravi's value for that sample is not applied, and the audit log records it.",
          why: "When two people measure the same sample, the later measurement wins, whichever reached the server first.",
        },
        {
          who: "Ravi, Inspector",
          action: "Submits for approval.",
          result: "The inspection continues as usual.",
          why: "Values still on a device are not part of the inspection until they arrive, so make sure the notice has cleared before submitting.",
        },
      ],
      takeaways: [
        "Values saved offline belong to the person who saved them, in that organisation. Someone else signing in on the tablet cannot see or send them.",
        "Signing out with values not sent asks first.",
        "If the inspection was submitted or closed in the meantime, the values are refused and can be discarded from the notice.",
      ],
    },
  ],
  ncrs: [
    {
      title: "Working the diameter failure",
      scenario: "Rejecting LOT-2026-00001 raised NCR-2026-00001, critical, with 300 kg rejected. Meera needs it investigated and closed properly.",
      steps: [
        {
          who: "Meera, Quality Manager",
          action: "Assigns NCR-2026-00001 to Dev, and picks the Dimensional category.",
          result: "It moves to Under investigation, and Dev is notified.",
          why: "Categories group NCRs in the Pareto chart, so recurring problems stand out.",
        },
        {
          who: "Dev, Quality Engineer",
          action: "Records containment: the 300 kg is tagged and moved to the reject area.",
          result: "The containment is on the record.",
          why: "Containment stops the problem spreading while the cause is still being found.",
        },
        {
          who: "Dev, Quality Engineer",
          action: "Records the root cause: the mill's rolling tolerance is too wide. Raises a CAPA from the NCR.",
          result: "The NCR is at Action required, with the CAPA linked.",
          why: "By default, a critical or major NCR cannot close without a closed CAPA. Settings can change which severities need one.",
        },
        {
          who: "Dev, Quality Engineer",
          action: "Once the CAPA is closed, requests verification.",
          result: "The NCR is Pending verification.",
          why: "Every linked CAPA must be closed or cancelled before the NCR can close.",
        },
        {
          who: "Meera, Quality Manager",
          action: "Checks the work and closes the NCR.",
          result: "Closed by someone other than Dev, so the rule is met.",
          why: "The person who closes must differ from the person who requested verification, so nobody signs off their own work.",
        },
      ],
      takeaways: [
        "Assign, contain, find the cause, act, verify, close.",
        "A reviewer can send an NCR back to Action required if the work is not good enough.",
      ],
    },
    {
      title: "A customer phones about scratched shafts",
      scenario: "Sahyadri Auto phones to say some shafts from DSP-2026-00001 arrived scratched. They will keep and polish them, so nothing comes back.",
      steps: [
        {
          who: "Meera, Quality Manager",
          action: "Opens DSP-2026-00001 and chooses Record complaint, with the customer's reference, the date, severity minor and what they reported.",
          result: "An NCR with source Customer complaint is raised, linked to the dispatch.",
          why: "Goods kept by the customer are a complaint. Goods sent back are a return, which goes through inspection instead.",
        },
        {
          who: "Meera, Quality Manager",
          action: "Assigns it to Dev, who records the cause: shafts were packed without separators.",
          result: "The NCR follows the usual life cycle.",
          why: "Every NCR follows the same steps, whatever its source, so one process covers them all.",
        },
        {
          who: "Dev, Quality Engineer",
          action: "Separately, raises an NCR by hand with source Process, naming work centre WC-TURN, for burrs found on the floor.",
          result: "The NCR belongs to Pune Plant, and shows the work centre as its place.",
          why: "Supplier and process NCRs name where the problem is, so analytics can show which supplier or work centre causes most of them.",
        },
      ],
      takeaways: [
        "NCRs come from inspections, audits, calibration, production, returns, complaints, suppliers, processes, or by hand.",
        "Complaints never touch stock. Only a return brings goods back.",
      ],
    },
  ],
  capas: [
    {
      title: "Stopping oversize bar",
      scenario: "NCR-2026-00001 found the mill's rolling tolerance too wide. Dev needs to fix the cause and prove the fix worked before the NCR can close.",
      steps: [
        {
          who: "Dev, Quality Engineer",
          action: "Raises a CAPA on NCR-2026-00001, with Dev as owner, due in 30 days.",
          result: "The CAPA is Open.",
          why: "Every CAPA has one owner and a target date, so it is clear who is responsible and by when.",
        },
        {
          who: "Dev, Quality Engineer",
          action: "Records the cause, then adds a corrective action to return the lot, and a preventive one, owned by Kiran, to agree a tighter tolerance with the mill.",
          result: "The plan is on record, and the CAPA moves to Implementation.",
          why: "Corrective actions deal with this case. Preventive ones stop it happening again, and each action can have its own owner and date.",
        },
        {
          who: "Kiran, Supplier Manager",
          action: "Attaches the mill's signed tolerance letter and completes the preventive action.",
          result: "The action is done, with its evidence attached.",
          why: "When Settings require evidence on each action, a file on the CAPA as a whole does not count.",
        },
        {
          who: "Dev, Quality Engineer",
          action: "With both actions complete, submits for verification.",
          result: "The CAPA waits at Effectiveness verification.",
          why: "Verification can only start once every action is complete.",
        },
        {
          who: "Meera, Quality Manager",
          action: "Checks the next three lots, finds them in limits, records the fix as effective, and closes the CAPA.",
          result: "The CAPA is Closed, and the NCR can now close.",
          why: "Someone other than the owner verifies, because the owner has a stake in the fix looking good.",
        },
      ],
      takeaways: [
        "Cause, plan, do, verify, close.",
        "If verification finds the fix did not work, the CAPA returns to Action planning and a new action is needed.",
      ],
    },
    {
      title: "A CAPA action runs late",
      scenario: "Acme's escalation setting is 7 days. Kiran's action to agree a new tolerance is due on 10 March, but the mill is slow to reply.",
      steps: [
        {
          who: "ProductionOS",
          action: "On 7 March, and again once 10 March passes.",
          result: "Kiran gets a reminder, then daily overdue reminders.",
          why: "Reminders go to the person who can do something about it first.",
        },
        {
          who: "ProductionOS",
          action: "On 17 March, the action is 7 days late.",
          result: "Everyone at Pune Plant who can approve CAPAs is told, once.",
          why: "Escalation brings in someone with authority when the owner alone has not been able to finish. It happens once, so it is not ignored as noise.",
        },
        {
          who: "Meera, Quality Manager",
          action: "Calls the mill, then moves the action's date to 31 March with a reason.",
          result: "The new date is on record, with the reason in the audit log.",
          why: "Dates can move, but never silently.",
        },
      ],
      takeaways: [
        "Set the escalation days under Settings. 0 turns escalation off.",
        "Escalation applies to the CAPA as a whole and to each of its actions.",
      ],
    },
  ],
  production: [
    {
      title: "Making 100 shafts",
      scenario: "LOT-2026-00002, a later delivery of EN8 bar, passed inspection and is released. Dev records a batch of 100 drive shafts made from it.",
      steps: [
        {
          who: "Dev, Quality Engineer",
          action: "Starts PB-2026-00001 for Drive Shaft at Pune Plant, and records 40 kg used from LOT-2026-00002.",
          result: "The batch records exactly what it used, and the lot shows 40 kg less.",
          why: "Only a released lot can be used, and only up to what remains. This is the link a trace follows from steel to shaft.",
        },
        {
          who: "Dev, Quality Engineer",
          action: "Completes the batch with 100 produced, and registers serial numbers SH-0001 to SH-0100.",
          result: "The batch is Completed and waits for final inspection.",
          why: "Serial numbers let a single shaft be found later. There cannot be more of them than units made.",
        },
        {
          who: "Ravi, Inspector, then Meera",
          action: "Ravi runs the final inspection, and it passes. Meera approves and records Accept.",
          result: "The batch is Released.",
          why: "A batch cannot be dispatched until final inspection releases it, just as a lot cannot be used until it is released.",
        },
      ],
      takeaways: [
        "Record consumption before completing a batch: a batch with nothing recorded cannot be completed.",
        "In-process checks can happen while the batch is in progress. Final inspection needs it completed.",
      ],
    },
    {
      title: "Sending bar to the Nashik plant",
      scenario: "Acme opens a second plant at Nashik, which needs 100 kg of released EN8 bar from Pune.",
      steps: [
        {
          who: "Nikhil, Stores Supervisor",
          action: "Under Stock transfers, sends 100 kg of LOT-2026-00002 from Pune Plant to Nashik Plant.",
          result: "STR-2026-00001 is In transit, and the 100 kg has left Pune's stock.",
          why: "Only released, unexpired stock not reserved for work orders can be sent. Reserved stock is already promised to an order.",
        },
        {
          who: "Stores at Nashik",
          action: "Receive the transfer the next day.",
          result: "A new lot, LOT-2026-00002-T1, appears at Nashik with the original receipt, supplier batch, inspection result and expiry.",
          why: "The bar was already inspected at Pune, so it arrives released. Its own number shows where it came from.",
        },
        {
          who: "Meera, Quality Manager",
          action: "Later runs a forward trace from LOT-2026-00002.",
          result: "The trace follows the transfer, and shows batches made from the bar at both plants.",
          why: "Transferred stock stays traceable, so a recall or hold reaches it wherever it went.",
        },
      ],
      takeaways: [
        "Up to 50 lines per transfer, each from a lot or a batch.",
        "A transfer in transit can be cancelled with a reason, and the stock goes back.",
        "A dispatch leaves from one plant. To ship stock from two plants together, transfer it first.",
      ],
    },
  ],
  "manufacturing-setup": [
    {
      title: "Defining how a shaft is made",
      scenario: "Before Acme can raise work orders, Arjun needs to describe where turning happens and what a shaft needs, and get it approved.",
      steps: [
        {
          who: "Arjun, Production Manager",
          action: "Adds work centre WC-TURN at Pune Plant, with labour and machine rates per hour, and lathe M-01.",
          result: "Turning can now be scheduled and costed.",
          why: "Work centre rates are where labour and machine cost come from. A missing rate is listed as missing, never guessed.",
        },
        {
          who: "Arjun, Production Manager",
          action: "Drafts BOM revision 1 for Drive Shaft: 1.05 kg of EN8 bar per shaft, with EN9 bar as an approved substitute. Submits it.",
          result: "Meera is notified that it needs approval.",
          why: "The 0.05 kg covers material lost in turning. The substitute can stand in one for one, but only when picked by hand.",
        },
        {
          who: "Meera, Quality Manager",
          action: "Sends it back: the scrap allowance should be 8%, not 5%.",
          result: "The BOM returns to draft with Meera's note, and Arjun is notified.",
          why: "Only a draft can change, so a submitted revision goes back to draft to be corrected.",
        },
        {
          who: "Arjun, then Meera",
          action: "Arjun corrects and resubmits it. Meera approves it from 1 April. Arjun drafts the routing, turn then grind, and Meera approves that too.",
          result: "From 1 April, work orders for Drive Shaft use revision 1 of both.",
          why: "Approval sets when a revision takes effect. A revision it replaces ends the day before, so there is always exactly one in force.",
        },
      ],
      takeaways: [
        "BOMs, recipes and routings all go draft, submitted, approved, then obsolete.",
        "The person who submitted cannot approve, except as a recorded self-review.",
      ],
    },
  ],
  "work-orders": [
    {
      title: "Making 200 shafts next week",
      scenario: "Sahyadri Auto has ordered 200 drive shafts for Friday. Kavya plans them at Pune Plant.",
      steps: [
        {
          who: "Kavya, Production Planner",
          action: "Plans 200 Drive Shafts at Pune Plant, due Friday. Arjun approves the plan.",
          result: "Work orders can be raised from the plan line.",
          why: "A plan shows what is meant to be made in a period. Work orders raised from it show how much of it is covered.",
        },
        {
          who: "Kavya, Production Planner",
          action: "Raises WO-2026-00014 for 200 from the line, and submits it.",
          result: "Arjun is notified that it needs approval.",
          why: "An order can only be edited while it is a draft. Submitting freezes what is being asked for.",
        },
        {
          who: "Arjun, Production Manager",
          action: "Approves and releases the order, then selects Reserve, which reserves what is free.",
          result: "The BOM and routing in effect are frozen onto the order, and 210 kg of bar is set aside, soonest expiring first.",
          why: "Freezing means a later BOM change does not alter an order already running. Reserving soonest expiring first uses older stock before it expires.",
        },
        {
          who: "Arjun, Production Manager",
          action: "Opens Scheduling.",
          result: "WC-TURN is overloaded on Wednesday.",
          why: "Scheduling compares load with shift capacity and warns, but the planner decides what to move.",
        },
        {
          who: "Arjun, Production Manager",
          action: "Starts the order.",
          result: "Batch PB-2026-00031 opens, and the first operation is ready on the shop floor.",
          why: "Starting opens the batch, so material issued from now on is recorded and traceable like any other batch.",
        },
      ],
      takeaways: [
        "Draft, submitted, approved, released, in progress, then completed or partially completed.",
        "Rescheduling needs a reason, and orders past their planned end send weekly reminders.",
      ],
    },
  ],
  "shop-floor": [
    {
      title: "A stop and a hold on WO-2026-00014",
      scenario: "WO-2026-00014 is in progress. Operation 10 is turning on lathe M-01, and operation 20 is grinding.",
      steps: [
        {
          who: "Suresh, Production Operator",
          action: "Starts operation 10 on lathe M-01 and reports 120 processed: 116 good, 4 rejected.",
          result: "Operation 20 can take the 116 good shafts.",
          why: "An operation can only process what came through good from the one before it, so rejected parts cannot reappear further down the line.",
        },
        {
          who: "Suresh, Production Operator",
          action: "Pauses with Machine breakdown: spindle seized.",
          result: "M-01 goes to Breakdown, a stop opens, and Arjun is notified.",
          why: "Every stop with a reason is downtime. It feeds availability in OEE and the downtime summary.",
        },
        {
          who: "Meera, Quality Manager",
          action: "Puts the order on quality hold: suspect gauge.",
          result: "Everything running pauses, and the batch is on hold until an in-process reinspection.",
          why: "A hold stops more suspect goods being made while the question is answered.",
        },
        {
          who: "Dev, Quality Engineer",
          action: "Raises a rework order for the 4 rejected, which Meera approves.",
          result: "The rework runs on the same batch, and closes when its reinspection passes.",
          why: "Rework stays on the same batch so its trace and costs stay together.",
        },
        {
          who: "Arjun, Production Manager",
          action: "Finds that Suresh mistyped a report: 102 processed instead of 120. Reverses it with a reason.",
          result: "The report is reversed, and Suresh reports again.",
          why: "Reports are never edited. A reversal keeps the original and shows who corrected it.",
        },
      ],
      takeaways: [
        "Report good, rejected and scrap together, and reports add up.",
        "Mandatory quality checkpoints are gates: an operation cannot start or complete until they pass.",
      ],
    },
  ],
  "production-completion": [
    {
      title: "Closing WO-2026-00014",
      scenario: "All operations on WO-2026-00014 are done. The last operation made 196 good shafts out of 200 ordered, and the order's tolerance is 1%.",
      steps: [
        {
          who: "Arjun, Production Manager",
          action: "Receives 196 shafts, with the reason 4 rejected at turning.",
          result: "The batch is completed, and its reservations are released.",
          why: "196 is below 200 less 1%, so a reason is needed. The quantity comes from the last operation's good output, not from a typed figure.",
        },
        {
          who: "Arjun, Production Manager",
          action: "Explains why 212 kg of bar was used against the 206 kg expected.",
          result: "The reason is kept with the order.",
          why: "Material used is compared with what the output needed. A line off by more than the allowed variance needs a reason.",
        },
        {
          who: "Ravi, Inspector, then Meera",
          action: "Ravi runs the final inspection. Meera records Accept.",
          result: "The batch is released for dispatch.",
          why: "By default an order cannot close until its batch's final inspection is decided.",
        },
        {
          who: "Arjun, Production Manager",
          action: "Closes the order, then opens Production analytics.",
          result: "It is Completed. Its cost and OEE appear, with turning's availability lowered by the spindle breakdown.",
          why: "OEE is availability × performance × quality. The breakdown cut availability, and the 4 rejects cut quality.",
        },
      ],
      takeaways: [
        "A completed order cannot be changed. Fix quantities through material corrections and returns.",
        "A figure that cannot be worked out shows as Not known, never as zero.",
      ],
    },
  ],
  "dispatches-and-returns": [
    {
      title: "Shipping, and a part return",
      scenario: "PB-2026-00001, 100 shafts, is released. Sahyadri Auto has ordered 60.",
      steps: [
        {
          who: "Dev, Quality Engineer",
          action: "Dispatches 60 shafts of PB-2026-00001 to Sahyadri Auto, as DSP-2026-00001, naming serials SH-0001 to SH-0060.",
          result: "The batch shows 40 still available.",
          why: "Naming serials lets a trace show which units each customer has. Without them, a trace shows the customer by batch.",
        },
        {
          who: "Dev, Quality Engineer",
          action: "Confirms the shipment, with the carrier reference.",
          result: "The dispatch counts as shipped.",
          why: "Until confirmed, traces flag the dispatch, and managers get a daily reminder after 3 days.",
        },
        {
          who: "Dev, Quality Engineer",
          action: "Records a return of 5 shafts as a quality complaint when they arrive.",
          result: "RET-2026-00001 waits for inspection, the 5 are not yet available, and a major NCR is raised.",
          why: "Returned goods never go back into stock until inspected, because nobody yet knows what happened to them.",
        },
        {
          who: "Ravi, Inspector, then Meera",
          action: "Ravi inspects them, and Meera accepts 3 and rejects 2.",
          result: "The return closes. 3 shafts are available again, and 2 are not.",
          why: "The decision is line by line, so good goods are not scrapped with bad.",
        },
      ],
      takeaways: [
        "Reverse a dispatch only for a mistake before the goods left. Anything that shipped and came back is a return.",
        "A customer who returned everything still shows as having received the batch, so a recall still reaches them.",
      ],
    },
  ],
  traceability: [
    {
      title: "A customer reports a cracked shaft",
      scenario: "Sahyadri Auto reports shaft SH-0042 cracked in service. Meera needs to know what it was made from and what else might be affected.",
      steps: [
        {
          who: "Meera, Quality Manager",
          action: "Enters SH-0042 in Serial search.",
          result: "It belongs to PB-2026-00001, shipped to Sahyadri Auto on DSP-2026-00001.",
          why: "The serial was named on the dispatch, so the unit can be found exactly.",
        },
        {
          who: "Meera, Quality Manager",
          action: "Opens the batch's backward trace.",
          result: "It shows LOT-2026-00002, from Konkan Bar Mills, supplier batch H-2240.",
          why: "Backward traces answer what went in: lots, receipts and suppliers.",
        },
        {
          who: "Meera, Quality Manager",
          action: "Runs a forward trace on that lot.",
          result: "It shows every batch made from the same steel, including LOT-2026-00002-T1 at Nashik, and every customer they reached.",
          why: "Forward traces answer who else is affected, following transfers to other plants.",
        },
        {
          who: "Meera, Quality Manager",
          action: "Opens the batch's timeline.",
          result: "Inspections, NCRs, transfers and corrections show in order, including a correction made to the lot's quantity.",
          why: "The timeline shows whether anything unusual happened before the batch shipped.",
        },
      ],
      takeaways: [
        "Backward traces show what went in. Forward traces show where it went.",
        "If the problem spread, start a recall from the affected batches.",
      ],
    },
  ],
  recalls: [
    {
      title: "Recalling a batch",
      scenario: "The cracked shaft points to a bad heat of steel. Meera decides to recall PB-2026-00001, of which 55 shafts are with Sahyadri Auto.",
      steps: [
        {
          who: "Meera, Quality Manager",
          action: "Starts RCL-2026-00001 from PB-2026-00001.",
          result: "Sahyadri Auto is listed from the dispatch records, with 55 shafts out, and the batch cannot be dispatched while the recall is open.",
          why: "Customers come from dispatch records, so nobody who received the batch is left off.",
        },
        {
          who: "Meera, Quality Manager",
          action: "Records what the regulator is told: hazard class II, the regulator, their reference and the date, and gives customers 5 days to respond.",
          result: "The regulator's report can be downloaded as a PDF.",
          why: "Some customers' sectors need the regulator told. ProductionOS keeps the record, but sending the notice is your responsibility.",
        },
        {
          who: "Meera, Quality Manager",
          action: "Logs a phone call to Sahyadri Auto, then their acknowledgement.",
          result: "The contact history is recorded with dates.",
          why: "Being reached is not acknowledging. A customer who has not acknowledged within the response days is escalated to recall approvers, once.",
        },
        {
          who: "Dev, Quality Engineer",
          action: "Records the 55 shafts as a customer return, and resolves the customer.",
          result: "Nothing is left out, so no blockers remain.",
          why: "Closure needs every customer resolved and goods left with customers explained.",
        },
        {
          who: "Meera, then Asha",
          action: "Meera requests closure, and Asha approves it.",
          result: "The recall is Closed, and the recall report is ready as a PDF.",
          why: "Someone other than the requester must approve closing a recall.",
        },
      ],
      takeaways: [
        "Batches found later can be added to an open recall, and its CAPA linked or changed.",
        "A customer who cannot be reached is recorded as an exception, with the attempts logged.",
      ],
    },
  ],
  corrections: [
    {
      title: "A quantity was mistyped",
      scenario: "Dev finds LOT-2026-00002 was entered as 400 kg, but the delivery note says 300 kg. The lot has already been inspected and part used.",
      steps: [
        {
          who: "Dev, Quality Engineer",
          action: "Raises MC-2026-00001 to change the quantity from 400 kg to 300 kg, with the delivery note attached.",
          result: "The correction waits for approval, and the lot is unchanged.",
          why: "A correction never changes the record until it is approved.",
        },
        {
          who: "Dev, Quality Engineer",
          action: "Reviews the impact shown: two batches used this lot.",
          result: "Dev acknowledges the impact before submitting.",
          why: "When material has gone further than the lot, the requester must see where before the change is decided.",
        },
        {
          who: "Meera, Quality Manager",
          action: "Approves. The lot has been inspected, so a second approver is needed.",
          result: "It still waits.",
          why: "Inspected lots carry a quality decision, so changing them needs two people besides the requester.",
        },
        {
          who: "Asha, Owner",
          action: "Gives the second approval.",
          result: "The quantity becomes 300 kg, and the original stays in the lot's history.",
          why: "The history keeps what was recorded first, so an auditor can see the correction and who approved it.",
        },
      ],
      takeaways: [
        "Corrections cover a lot's material, supplier, supplier batch or quantity, and which lot a batch really used.",
        "A correction does not start a new inspection. The approvers decide whether one is needed.",
      ],
    },
  ],
  calibration: [
    {
      title: "A caliper fails calibration",
      scenario: "Caliper VC-9 is used on most incoming inspections at Pune Plant. Its six-monthly calibration is due.",
      steps: [
        {
          who: "Dev, Quality Engineer",
          action: "Registers caliper VC-9 with a 180-day frequency, and prints its QR label.",
          result: "VC-9 is Active, with a due date.",
          why: "Scanning the label opens the instrument, so an inspector can check it is in date before using it.",
        },
        {
          who: "Ravi, Inspector",
          action: "Records VC-9 on inspections over the next months.",
          result: "Each measurement names the instrument that took it.",
          why: "An overdue or out-of-service instrument cannot be recorded, so only instruments believed good are used.",
        },
        {
          who: "Dev, Quality Engineer",
          action: "Records a calibration on VC-9 with result Fail.",
          result: "VC-9 goes out of service, and a major NCR lists every inspection it was used on since its last good calibration.",
          why: "A failed instrument may have been wrong for some time, so every result since its last good calibration is in doubt.",
        },
        {
          who: "Meera, Quality Manager",
          action: "Opens Inspections in doubt. Decides Result stands for one where the values were far inside limits, and Reinspect for one close to a limit.",
          result: "The first stands, with the reason. The second's lot is held, and a new inspection starts.",
          why: "Results far from the limits would still pass with a small error. Those close to a limit might not.",
        },
        {
          who: "Meera, Quality Manager",
          action: "Sees that the reinspected lot was partly used in a batch already shipped.",
          result: "The batches and customers it reached are shown, with a recall report for each batch.",
          why: "Goods already used or dispatched are not recalled automatically. You decide whether a recall is needed.",
        },
      ],
      takeaways: [
        "The NCR cannot close until every inspection in doubt has a decision.",
        "A reminder goes out 14 days before an instrument is due, and again once overdue.",
      ],
    },
  ],
  audits: [
    {
      title: "An internal audit finds a missing label",
      scenario: "Priya runs Acme's internal audits. Measurement control at Pune Plant is due for its quarterly audit.",
      steps: [
        {
          who: "Priya, Auditor",
          action: "Builds a Measurement checklist, and plans an internal audit of Pune Plant, repeating every quarter.",
          result: "The audit is Planned, and the lead auditor is told.",
          why: "Questions are copied into the audit, so later changes to the checklist leave this audit alone.",
        },
        {
          who: "Priya, Auditor",
          action: "Answers the checklist, marking one question nonconforming.",
          result: "Caliper VC-9 was in use without a calibration label.",
          why: "Every question needs an answer: conforming, nonconforming or not applicable.",
        },
        {
          who: "Priya, Auditor",
          action: "Records a minor nonconformity, and raises its NCR.",
          result: "An NCR with source Audit follows the normal closing rules.",
          why: "Findings are fixed through NCRs, so the same process handles every problem.",
        },
        {
          who: "Meera, Quality Manager",
          action: "Approves the closure Priya requested.",
          result: "The audit is Closed, its PDF report is available, and next quarter's audit is planned.",
          why: "Someone else approves closing an audit, and a repeating audit plans its next one on closing.",
        },
      ],
      takeaways: [
        "A recorded finding cannot be edited. Handle a wrong one by cancelling its NCR.",
        "An audit with findings cannot be cancelled.",
      ],
    },
  ],
  analytics: [
    {
      title: "Finding the worst supplier",
      scenario: "Meera is preparing the quarterly quality review and wants to know where most problems come from.",
      steps: [
        {
          who: "Meera, Quality Manager",
          action: "Opens Analytics, and sets the last quarter for Pune Plant.",
          result: "Supplier scorecards show Konkan Bar Mills with 1 of 4 lots rejected: 25%.",
          why: "Supplier lot rejection counts lots with a final disposition. A lot on hold is not counted until it is decided.",
        },
        {
          who: "Meera, Quality Manager",
          action: "Looks at the Defect Pareto.",
          result: "Dimensional leads, with most of the running total.",
          why: "A Pareto chart puts the biggest categories first, so effort goes where it fixes the most.",
        },
        {
          who: "Meera, Quality Manager",
          action: "Notices the defect rate shows No data for one material.",
          result: "No result for that material passed or failed in the period.",
          why: "No data means there was nothing to measure. It is not the same as 0%.",
        },
        {
          who: "Meera, Quality Manager",
          action: "Downloads the Supplier quality report for the review meeting.",
          result: "An Excel file with the same figures, and the export is audited.",
          why: "Every download is written to the audit log, so it is known who took the figures out and when.",
        },
      ],
      takeaways: [
        "Each chart has a table view under it.",
        "Each figure shows only to people who may view its kind of record.",
      ],
    },
  ],
  reports: [
    {
      title: "Preparing for a customer visit",
      scenario: "Sahyadri Auto's quality team visits next week. Meera wants the calibration register and six months of NCRs.",
      steps: [
        {
          who: "Meera, Quality Manager",
          action: "Chooses the Calibration report for Pune Plant.",
          result: "Every active instrument shows, soonest due first.",
          why: "The calibration report is the register as it stands today, so the period does not apply.",
        },
        {
          who: "Meera, Quality Manager",
          action: "Chooses the NCR report for six months, previews it, and downloads the PDF.",
          result: "A landscape PDF with page numbers and repeated headings.",
          why: "Preview shows the first 100 rows, so the right choice can be checked before downloading everything.",
        },
      ],
      takeaways: [
        "Every download is written to the audit log.",
        "Audits, recalls and single inspections have their own detailed PDFs on the record itself.",
      ],
    },
    {
      title: "Exporting two years of inspections",
      scenario: "Acme's owner wants every inspection for the last two years in one file, about 40,000 rows.",
      steps: [
        {
          who: "Meera, Quality Manager",
          action: "Chooses the Inspection report for two years.",
          result: "The screen says the report is over 5,000 rows.",
          why: "The screen and the PDF stop at 5,000 rows, because longer tables are hard to read and slow to load.",
        },
        {
          who: "Meera, Quality Manager",
          action: "Selects Export every row.",
          result: "The export is queued, and Meera carries on with other work.",
          why: "Large exports run in the background, so the page does not have to wait. Each person can run three at a time.",
        },
        {
          who: "ProductionOS",
          action: "Finishes the export.",
          result: "Meera is notified, and the file is under Your exports.",
          why: "Permissions are checked again when the export runs, so someone who lost access meanwhile gets nothing.",
        },
        {
          who: "Meera, Quality Manager",
          action: "Downloads the Excel file and sends it to Asha.",
          result: "The download is in the audit log.",
          why: "Only the person who asked can download an export, and only for 7 days.",
        },
      ],
      takeaways: [
        "Use background exports for long periods, up to 1,000,000 rows.",
        "Download within 7 days. After that the file is removed.",
      ],
    },
  ],
  notifications: [
    {
      title: "Quieter email",
      scenario: "Meera approves dozens of inspections a day, and her inbox is full of approval requests she already sees in the app.",
      steps: [
        {
          who: "Meera, Quality Manager",
          action: "In My account, turns off email for inspections awaiting approval.",
          result: "Those stop arriving by email, and still appear under the bell.",
          why: "In-app notifications cannot be turned off, so nothing is ever missed, only kept out of email.",
        },
        {
          who: "Meera, Quality Manager",
          action: "Keeps email on for failed critical inspections and CAPA escalations.",
          result: "Those still arrive by email.",
          why: "Urgent problems are worth an email even when routine ones are not.",
        },
      ],
      takeaways: [
        "Choose email by type, and keep it for what needs a response quickly.",
        "The bell's count updates as soon as something new arrives.",
      ],
    },
  ],
  billing: [
    {
      title: "Moving from the trial to Growth",
      scenario: "Acme's Professional trial ends soon. Asha decides Growth is enough for now.",
      steps: [
        {
          who: "Asha, Owner",
          action: "Under Billing, picks Annual and chooses Growth.",
          result: "BILL-2026-00001 is issued, and the trial carries on.",
          why: "A plan only starts when its invoice is paid, so nothing changes by choosing.",
        },
        {
          who: "Asha, Owner",
          action: "Pays the invoice online.",
          result: "The payment is confirmed, Growth starts, and the invoice shows Paid.",
          why: "If the payment page closes before returning, the payment is still checked with the payment provider and applied.",
        },
        {
          who: "Asha, Owner",
          action: "Opens Production.",
          result: "It is not part of Growth. Records made during the trial stay readable, but nothing can be added.",
          why: "A plan decides what an organisation has bought. Nothing made earlier is deleted.",
        },
      ],
      takeaways: [
        "Choosing again replaces any unpaid invoice.",
        "A move to a smaller plan is refused if you are over its limits.",
      ],
    },
    {
      title: "Upgrading partway through a year",
      scenario: "Six months into a Growth year, Acme starts production and needs Professional.",
      steps: [
        {
          who: "Asha, Owner",
          action: "Chooses Professional, annual.",
          result: "The invoice shows the price, less the unused value of the Growth year.",
          why: "Time already paid for is not lost. Its unused value is taken off the new plan.",
        },
        {
          who: "Asha, Owner",
          action: "Pays the invoice.",
          result: "Professional starts once the payment is recorded.",
          why: "When the credit is larger than the new price, the invoice is settled at once and the rest is added as extra days.",
        },
        {
          who: "ProductionOS",
          action: "Seven days before the year ends.",
          result: "A renewal invoice is raised, and Asha is told.",
          why: "Paying before the end keeps everything running. If time runs out, there is a grace period, then read-only, and nothing is deleted.",
        },
      ],
      takeaways: [
        "Reminders go out 7, 3 and 1 days before a subscription ends.",
        "In read-only, you can still read and export. Paying restores access.",
      ],
    },
  ],
  security: [
    {
      title: "A locked account",
      scenario: "Ravi has forgotten his password after a holiday.",
      steps: [
        {
          who: "Ravi, Inspector",
          action: "Enters the wrong password ten times.",
          result: "Sign-in from that tablet is blocked for 15 minutes, and Ravi is emailed.",
          why: "Blocking slows down anyone guessing passwords. It applies only where the wrong passwords came from, so a stranger trying Ravi's email elsewhere cannot keep Ravi out.",
        },
        {
          who: "Ravi, Inspector",
          action: "Uses Forgot password, and sets a new one from the emailed link.",
          result: "The account is unlocked straight away, and the change is in the audit log.",
          why: "The emailed link proves Ravi controls the account's email, so waiting is no longer needed.",
        },
      ],
      takeaways: ["Sign-in and password events are kept in the audit log for each member."],
    },
    {
      title: "Letting support look into a problem",
      scenario: "Asha reports that a report looks wrong. ProductionOS support needs to see Acme's records to investigate.",
      steps: [
        {
          who: "Asha, Owner",
          action: "Under Settings, Support access, allows access for 24 hours, read only.",
          result: "Support can open a session into Acme's account for that time.",
          why: "Support sees nothing of your records unless you allow it, and only for the time you choose: 24 hours, 3 days or 7 days.",
        },
        {
          who: "ProductionOS support",
          action: "Opens a session, with a reason, and reads the report's records.",
          result: "Every page support opens is listed under Support access, with their reason.",
          why: "You can see exactly what support looked at.",
        },
        {
          who: "Asha, Owner",
          action: "Support asks to fix a setting. Asha grants access again for 24 hours, allowing changes.",
          result: "Support's changes are in the audit log as support's.",
          why: "Changes are only allowed on a 24-hour grant, to keep that window short.",
        },
        {
          who: "Asha, Owner",
          action: "Revokes access once the fix is confirmed.",
          result: "Any open support session ends at once.",
          why: "Access can be withdrawn at any time, without waiting for it to run out.",
        },
      ],
      takeaways: [
        "Support access is off unless an administrator allows it.",
        "Read-only is the default. Changes are allowed only on a 24-hour grant.",
      ],
    },
  ],
  platform: [
    {
      title: "Recording a bank transfer",
      scenario: "Acme Forgings paid BILL-2026-00001 by bank transfer instead of online.",
      steps: [
        {
          who: "A billing administrator",
          action: "Finds Acme Forgings under Organisations, with BILL-2026-00001 unpaid.",
          result: "The organisation's invoices and payments show.",
          why: "Unpaid invoices are listed first, so they are not missed.",
        },
        {
          who: "A billing administrator",
          action: "Enters the password again, then records the payment with the bank reference.",
          result: "The invoice shows Paid, the plan starts, and Acme's owner is told.",
          why: "Recording money is a critical action, so it asks for the password again. A bank reference can only be used once.",
        },
      ],
      takeaways: ["Every Control Center action that changes an organisation needs a reason, and is audited."],
    },
    {
      title: "Refunding part of an invoice",
      scenario: "Acme was billed for a year but agreed with the sales team to pay for nine months. A quarter of the invoice is to be refunded.",
      steps: [
        {
          who: "A billing administrator",
          action: "Opens the paid invoice and raises a credit note for the amount, with the reason, to be refunded through the gateway.",
          result: "The credit note is saved, and the refund is sent to Razorpay after that.",
          why: "Saving first means that a refund already asked for is never asked for twice, even if something fails halfway.",
        },
        {
          who: "Razorpay",
          action: "Takes a while to confirm.",
          result: "The refund shows Pending.",
          why: "A refund is not counted as made until Razorpay confirms it, by webhook, by Check refund, or by the refunds job every 15 minutes.",
        },
        {
          who: "ProductionOS",
          action: "Hears from Razorpay that the refund succeeded.",
          result: "The credit note shows Refunded, and Acme sees it and its PDF under Billing.",
          why: "If Razorpay reports a failure, the credit note shows it, and the money can be returned by bank instead.",
        },
      ],
      takeaways: [
        "A credit note can be up to what is left of the invoice.",
        "A payment that no invoice could take waits under Payments waiting for review, and can be refunded from there the same way.",
      ],
    },
  ],
};
