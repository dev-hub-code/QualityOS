import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { Suspense, type ReactNode } from "react";
import { LotPaths } from "@/components/landing/lot-paths";
import { Pricing, type PublicPlan } from "@/components/landing/pricing";
import { buttonVariants } from "@/components/ui/button";
import { backendUrl } from "@/lib/bff";

export const metadata: Metadata = {
  description:
    "Incoming inspection, NCR and CAPA, batch traceability, calibration and audits, in one connected record.",
};

const WORKFLOW = [
  ["Receive", "Log each delivery against its purchase order. Every lot gets its own internal batch number."],
  ["Inspect", "Work through a published template on a phone, with limits checked as results go in."],
  ["Decide", "A second person approves the result and records the disposition."],
  ["Correct", "A failure raises an NCR, and containment, root cause and CAPA follow to a verified close."],
  ["Produce", "Record which lots went into which batch, with serial numbers where you need them."],
  ["Trace", "Go from a lot to its customers, or back again, and run a recall from what you find."],
];

const CONTROLS = [
  ["Failed material stays held", "A failed lot moves on only through a recorded conditional acceptance, never by default."],
  ["No one approves their own work", "Inspections, NCR closure and CAPA verification each need a second person."],
  ["Specifications keep their history", "An inspection stays tied to the template version it was carried out against."],
  ["Corrections leave a trail", "A wrong lot record is put right by two approvers, and the original stays visible."],
];

const MODULES = [
  ["Incoming and in-process inspection", "Template versions, sampling, evidence and printable reports."],
  ["NCR and CAPA", "Severity, containment, root cause, actions, due dates and effectiveness checks."],
  ["Batch traceability", "Forward and backward traces, serial search, dispatch, returns and recalls."],
  ["Calibration", "Instrument register, QR labels, due reminders and inspections flagged after a failed calibration."],
  ["Quality audits", "Checklists, findings that raise NCRs, and a generated audit report."],
  ["Analytics and reports", "Pass rate, first pass yield, supplier scorecards, Pareto, and PDF or Excel exports."],
];

/** Read at request time, because the backend cannot be reached while the image is being built. */
async function LivePricing() {
  await connection();
  return <Pricing plans={await livePlans()} />;
}

async function livePlans(): Promise<PublicPlan[] | null> {
  try {
    const response = await fetch(`${backendUrl()}/api/v1/public/plans`, { next: { revalidate: 60 } });
    return response.ok ? (await response.json()).data : null;
  } catch {
    return null;
  }
}

function Heading({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="mb-10 max-w-2xl">
      <h2 className="text-3xl font-semibold tracking-tight text-balance sm:text-4xl">{title}</h2>
      {children && <p className="mt-3 text-lg text-muted-foreground">{children}</p>}
    </div>
  );
}

export default function Home() {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <header className="border-b">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-x-6 gap-y-2 px-4 py-3 sm:px-6">
          <Link href="/" className="text-lg font-semibold text-primary">
            QualityOS
          </Link>
          <nav aria-label="Page" className="order-last flex w-full gap-5 text-sm text-muted-foreground sm:order-none sm:w-auto">
            <a className="hover:text-foreground" href="#workflow">How it works</a>
            <a className="hover:text-foreground" href="#product">Product</a>
            <a className="hover:text-foreground" href="#pricing">Pricing</a>
            <Link className="hover:text-foreground" href="/docs">Docs</Link>
          </nav>
          <div className="flex items-center gap-2">
            <Link href="/login" className={buttonVariants({ variant: "ghost", size: "sm" })}>
              Sign in
            </Link>
            <Link href="/register" className={buttonVariants({ size: "sm" })}>
              Start a trial
            </Link>
          </div>
        </div>
      </header>

      <main>
        <section className="mx-auto grid max-w-6xl items-center gap-10 px-4 py-14 sm:px-6 lg:grid-cols-[1.05fr_1fr] lg:py-24">
          <div>
            <p className="text-sm font-medium text-primary">Quality and traceability for Indian manufacturers</p>
            <h1 className="mt-3 text-4xl font-semibold tracking-tight text-balance sm:text-5xl">
              Know what passed, what failed, and where every lot went.
            </h1>
            <p className="mt-5 max-w-xl text-lg text-muted-foreground">
              QualityOS replaces inspection registers and spreadsheets with one connected record, from the goods-in bay
              to the customer who received the batch.
            </p>
            <div className="mt-8 flex flex-wrap gap-3">
              <Link href="/register" className={buttonVariants({ size: "lg" })}>
                Start a trial
              </Link>
              <a href="#workflow" className={buttonVariants({ variant: "outline", size: "lg" })}>
                See how it works
              </a>
            </div>
          </div>
          <LotPaths />
        </section>

        <section id="workflow" className="scroll-mt-16 border-t bg-muted/40">
          <div className="mx-auto max-w-6xl px-4 py-16 sm:px-6">
            <Heading title="One record, from delivery to dispatch">
              Each step picks up where the last one stopped, so nobody retypes a batch number.
            </Heading>
            <ol className="grid gap-x-8 gap-y-8 sm:grid-cols-2 lg:grid-cols-3">
              {WORKFLOW.map(([title, text], index) => (
                <li key={title} className="flex gap-4">
                  <span aria-hidden className="text-2xl font-semibold text-muted-foreground tabular-nums">
                    {index + 1}
                  </span>
                  <div>
                    <h3 className="font-semibold">{title}</h3>
                    <p className="mt-1 text-sm text-muted-foreground">{text}</p>
                  </div>
                </li>
              ))}
            </ol>
          </div>
        </section>

        <section className="mx-auto max-w-6xl px-4 py-16 sm:px-6">
          <Heading title="The rules are built in, not written on the wall">
            The system refuses the shortcut, so the procedure holds on a busy shift too.
          </Heading>
          <dl className="grid gap-x-10 sm:grid-cols-2">
            {CONTROLS.map(([title, text]) => (
              <div key={title} className="border-t py-5">
                <dt className="font-semibold">{title}</dt>
                <dd className="mt-1 text-sm text-muted-foreground">{text}</dd>
              </div>
            ))}
          </dl>
        </section>

        <section id="product" className="scroll-mt-16 border-t bg-muted/40">
          <div className="mx-auto max-w-6xl px-4 py-16 sm:px-6">
            <Heading title="What is in the product" />
            <dl className="grid gap-x-10 gap-y-6 sm:grid-cols-2 lg:grid-cols-3">
              {MODULES.map(([title, text]) => (
                <div key={title}>
                  <dt className="font-semibold">{title}</dt>
                  <dd className="mt-1 text-sm text-muted-foreground">{text}</dd>
                </div>
              ))}
            </dl>
          </div>
        </section>

        <section className="mx-auto max-w-6xl px-4 py-16 sm:px-6">
          <Heading title="Your records stay yours">
            Each organisation is kept apart in the database itself, not only in the application. Access follows roles,
            critical actions are written to an audit log, and if a subscription ends, your
            records can still be read and exported.
          </Heading>
        </section>

        <section id="pricing" className="scroll-mt-16 border-t">
          <div className="mx-auto max-w-6xl px-4 py-16 sm:px-6">
            <Heading title="Pricing" />
            <Suspense fallback={<p className="text-muted-foreground">Loading prices…</p>}>
              <LivePricing />
            </Suspense>
          </div>
        </section>
      </main>

      <footer className="border-t">
        <div className="mx-auto flex max-w-6xl flex-wrap justify-between gap-3 px-4 py-6 text-sm text-muted-foreground sm:px-6">
          <p>QualityOS supports your quality system. It does not certify it or confer any regulatory approval.</p>
          <Link className="hover:text-foreground" href="/status">
            System status
          </Link>
        </div>
      </footer>
    </div>
  );
}
