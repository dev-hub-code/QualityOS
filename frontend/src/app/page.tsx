import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";
import { Suspense, type ReactNode } from "react";
import { LotPaths } from "@/components/landing/lot-paths";
import { Pricing, type PublicPlan } from "@/components/landing/pricing";
import {
  ArrowRight,
  Ban,
  ChartColumn,
  ClipboardCheck,
  Database,
  Factory,
  FileClock,
  FileDown,
  Gauge,
  History,
  ListChecks,
  type LucideIcon,
  Network,
  PackageCheck,
  Route,
  ScrollText,
  ShieldCheck,
  Smartphone,
  TriangleAlert,
  UserCheck,
  Users,
  Wrench,
} from "lucide-react";
import { buttonVariants } from "@/components/ui/button";
import { backendUrl } from "@/lib/bff";

export const metadata: Metadata = {
  description:
    "Incoming inspection, NCR and CAPA, batch traceability, calibration and audits, in one connected record.",
};

const WORKFLOW: [LucideIcon, string, string][] = [
  [PackageCheck, "Receive", "Log each delivery against its purchase order. Every lot gets its own internal batch number."],
  [Smartphone, "Inspect", "Work through a published template on a phone, with limits checked as results go in."],
  [UserCheck, "Decide", "A second person approves the result and records the disposition."],
  [Wrench, "Correct", "A failure raises an NCR, and containment, root cause and CAPA follow to a verified close."],
  [Factory, "Produce", "Record which lots went into which batch, with serial numbers where you need them."],
  [Route, "Trace", "Go from a lot to its customers, or back again, and run a recall from what you find."],
];

const CONTROLS: [LucideIcon, string, string][] = [
  [Ban, "Failed material stays held", "A failed lot moves on only through a recorded conditional acceptance, never by default."],
  [Users, "No one approves their own work", "Inspections, NCR closure and CAPA verification each need a second person."],
  [History, "Specifications keep their history", "An inspection stays tied to the template version it was carried out against."],
  [FileClock, "Corrections leave a trail", "A wrong lot record is put right by two approvers, and the original stays visible."],
];

const MODULES: [LucideIcon, string, string][] = [
  [ClipboardCheck, "Incoming and in-process inspection", "Template versions, sampling, evidence and printable reports."],
  [TriangleAlert, "NCR and CAPA", "Severity, containment, root cause, actions, due dates and effectiveness checks."],
  [Network, "Batch traceability", "Forward and backward traces, serial search, dispatch, returns and recalls."],
  [Gauge, "Calibration", "Instrument register, QR labels, due reminders and inspections flagged after a failed calibration."],
  [ListChecks, "Quality audits", "Checklists, findings that raise NCRs, and a generated audit report."],
  [ChartColumn, "Analytics and reports", "Pass rate, first pass yield, supplier scorecards, Pareto, and PDF or Excel exports."],
];

const HIGHLIGHTS: [LucideIcon, string][] = [
  [ClipboardCheck, "Inspection"],
  [TriangleAlert, "NCR and CAPA"],
  [Network, "Traceability"],
];

const SAFEGUARDS: [LucideIcon, string, string][] = [
  [Database, "Kept apart in the database", "Each organisation is kept apart in the database itself, not only in the application."],
  [ScrollText, "Every critical action logged", "Access follows roles, and critical actions are written to an audit log."],
  [FileDown, "Readable after you leave", "If a subscription ends, your records can still be read and exported."],
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

function Heading({ eyebrow, title, children, center }: { eyebrow?: string; title: string; children?: ReactNode; center?: boolean }) {
  return (
    <div className={center ? "mx-auto mb-12 max-w-2xl text-center" : "mb-12 max-w-2xl"}>
      {eyebrow && <p className="mb-3 text-sm font-semibold tracking-wide text-[#3b56b0] uppercase">{eyebrow}</p>}
      <h2 className="text-3xl font-bold tracking-tight text-balance text-[#1b2a55] sm:text-4xl">{title}</h2>
      {children && <p className="mt-4 text-lg text-pretty text-muted-foreground">{children}</p>}
    </div>
  );
}

function IconTile({ icon: Icon, dark }: { icon: LucideIcon; dark?: boolean }) {
  return (
    <span
      aria-hidden
      className={
        dark
          ? "grid size-11 shrink-0 place-items-center rounded-2xl bg-white/10 text-white ring-1 ring-white/20"
          : "grid size-11 shrink-0 place-items-center rounded-2xl bg-gradient-to-br from-[#2f4590] to-[#16224f] text-white shadow-[0_8px_18px_-8px_rgba(28,42,98,0.7)]"
      }
    >
      <Icon className="size-5" />
    </span>
  );
}

export default function Home() {
  return (
    <div className="min-h-screen overflow-x-clip text-foreground">
      <header className="sticky top-0 z-30 border-b border-white/70 bg-white/60 backdrop-blur-lg">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-x-6 gap-y-2 px-4 py-3 sm:px-6">
          <Link href="/" className="flex items-center gap-2 text-lg font-bold tracking-tight text-[#1b2a55]">
            <span aria-hidden className="grid size-8 place-items-center rounded-xl bg-gradient-to-br from-[#2f4590] to-[#16224f] text-white shadow">
              <ShieldCheck className="size-4" />
            </span>
            QualityOS
          </Link>
          <nav aria-label="Page" className="order-last flex w-full gap-5 text-sm text-muted-foreground sm:order-none sm:w-auto">
            <a className="transition-colors hover:text-foreground" href="#workflow">How it works</a>
            <a className="transition-colors hover:text-foreground" href="#product">Product</a>
            <a className="transition-colors hover:text-foreground" href="#pricing">Pricing</a>
            <Link className="transition-colors hover:text-foreground" href="/docs">Docs</Link>
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
        <section className="relative">
          <div aria-hidden className="pointer-events-none absolute -top-24 right-0 -z-10 size-[36rem] rounded-full bg-[#9fb2dc]/40 blur-3xl" />
          <div className="mx-auto grid max-w-6xl items-center gap-14 px-4 pt-14 pb-20 sm:px-6 lg:grid-cols-[1.05fr_1fr] lg:pt-24 lg:pb-28">
            <div className="motion-safe:animate-in motion-safe:fade-in motion-safe:slide-in-from-bottom-4 motion-safe:duration-700">
              <p className="inline-flex items-center gap-2 rounded-full bg-white/70 px-3 py-1 text-xs font-medium text-[#1e2d5a] shadow-sm ring-1 ring-white/80 backdrop-blur">
                <span className="relative flex size-2">
                  <span className="absolute inline-flex size-full rounded-full bg-[#3b56b0] opacity-60 motion-safe:animate-ping" />
                  <span className="relative inline-flex size-2 rounded-full bg-[#1e2d5a]" />
                </span>
                Quality and traceability for Indian manufacturers
              </p>
              <h1 className="mt-6 text-4xl leading-[1.08] font-bold tracking-tight text-balance text-[#1b2a55] sm:text-5xl lg:text-[3.5rem]">
                Know what passed, what failed,{" "}
                <span className="bg-gradient-to-r from-[#5b6785] to-[#97a3bd] bg-clip-text text-transparent">and where every lot went.</span>
              </h1>
              <p className="mt-6 max-w-xl text-lg text-pretty text-muted-foreground">
                QualityOS replaces inspection registers and spreadsheets with one connected record, from the goods-in bay
                to the customer who received the batch.
              </p>
              <div className="mt-8 flex flex-wrap gap-3">
                <Link href="/register" className={buttonVariants({ size: "lg", className: "h-11 px-5" })}>
                  Start a trial
                  <ArrowRight data-icon="inline-end" className="transition-transform group-hover/button:translate-x-0.5" />
                </Link>
                <a href="#workflow" className={buttonVariants({ variant: "outline", size: "lg", className: "h-11 px-5" })}>
                  See how it works
                </a>
              </div>
              <ul className="mt-10 flex flex-wrap gap-x-6 gap-y-3 text-sm font-medium text-[#1e2d5a]">
                {HIGHLIGHTS.map(([Icon, label]) => (
                  <li key={label} className="flex items-center gap-2">
                    <span aria-hidden className="grid size-8 place-items-center rounded-xl bg-white/70 shadow-sm ring-1 ring-white/80">
                      <Icon className="size-4" />
                    </span>
                    {label}
                  </li>
                ))}
              </ul>
            </div>
            <div className="motion-safe:animate-in motion-safe:fade-in motion-safe:slide-in-from-bottom-8 motion-safe:delay-150 motion-safe:duration-1000 motion-safe:fill-mode-both">
              <LotPaths />
            </div>
          </div>
        </section>

        <section id="workflow" className="glass mx-4 scroll-mt-20 rounded-[2rem] sm:mx-6 xl:mx-auto xl:max-w-6xl">
          <div className="mx-auto max-w-6xl px-5 py-16 sm:px-10 sm:py-20">
            <Heading eyebrow="How it works" title="One record, from delivery to dispatch">
              Each step picks up where the last one stopped, so nobody retypes a batch number.
            </Heading>
            <ol className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              {WORKFLOW.map(([Icon, title, text], index) => (
                <li
                  key={title}
                  className="group relative rounded-2xl bg-white/60 p-5 ring-1 ring-white/80 transition duration-300 hover:-translate-y-0.5 hover:bg-white/90 hover:shadow-[0_18px_40px_-20px_rgba(30,41,82,0.5)]"
                >
                  <div className="flex items-center justify-between">
                    <IconTile icon={Icon} />
                    <span aria-hidden className="text-3xl font-bold text-[#1b2a55]/10 tabular-nums">
                      {String(index + 1).padStart(2, "0")}
                    </span>
                  </div>
                  <h3 className="mt-4 font-semibold">{title}</h3>
                  <p className="mt-1 text-sm text-muted-foreground">{text}</p>
                </li>
              ))}
            </ol>
          </div>
        </section>

        <section className="mx-auto max-w-6xl px-4 py-24 sm:px-6">
          <div className="grid gap-12 lg:grid-cols-[0.9fr_1.1fr] lg:items-start">
            <div className="lg:sticky lg:top-28">
              <Heading eyebrow="Controls" title="The rules are built in, not written on the wall">
                The system refuses the shortcut, so the procedure holds on a busy shift too.
              </Heading>
            </div>
            <dl className="grid gap-4 sm:grid-cols-2">
              {CONTROLS.map(([Icon, title, text]) => (
                <div key={title} className="glass rounded-2xl p-6">
                  <IconTile icon={Icon} />
                  <dt className="mt-4 font-semibold">{title}</dt>
                  <dd className="mt-1 text-sm text-muted-foreground">{text}</dd>
                </div>
              ))}
            </dl>
          </div>
        </section>

        <section id="product" className="mx-auto max-w-6xl scroll-mt-20 px-4 sm:px-6">
          <Heading eyebrow="Product" title="What is in the product" center />
          <dl className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {MODULES.map(([Icon, title, text]) => (
              <div
                key={title}
                className="glass group rounded-2xl p-6 transition duration-300 hover:-translate-y-1 hover:shadow-[0_24px_50px_-20px_rgba(30,41,82,0.45)]"
              >
                <IconTile icon={Icon} />
                <dt className="mt-4 font-semibold">{title}</dt>
                <dd className="mt-1 text-sm text-muted-foreground">{text}</dd>
              </div>
            ))}
          </dl>
        </section>

        <section className="mx-4 mt-24 sm:mx-6 xl:mx-auto xl:max-w-6xl">
          <div className="relative overflow-hidden rounded-[2rem] bg-gradient-to-br from-[#2d4593] via-[#1f2f6b] to-[#121b42] px-6 py-16 text-white shadow-[0_30px_60px_-25px_rgba(20,31,74,0.7)] sm:px-12">
            <div aria-hidden className="pointer-events-none absolute -top-32 -right-24 size-96 rounded-full bg-[#6f88d6]/30 blur-3xl" />
            <div aria-hidden className="pointer-events-none absolute inset-0 bg-[radial-gradient(40rem_20rem_at_0%_0%,rgba(255,255,255,0.12),transparent_60%)]" />
            <div className="relative grid gap-12 lg:grid-cols-[0.9fr_1.1fr] lg:items-center">
              <div>
                <p className="mb-3 text-sm font-semibold tracking-wide text-[#a9baf0] uppercase">Security</p>
                <h2 className="text-3xl font-bold tracking-tight text-balance sm:text-4xl">Your records stay yours</h2>
                <p className="mt-4 text-lg text-white/70">
                  These protections are built into how QualityOS keeps your data, not left to a setting.
                </p>
              </div>
              <ul className="grid gap-5">
                {SAFEGUARDS.map(([Icon, title, text]) => (
                  <li key={title} className="flex gap-4 rounded-2xl bg-white/[0.06] p-5 ring-1 ring-white/10 backdrop-blur">
                    <IconTile icon={Icon} dark />
                    <div>
                      <h3 className="font-semibold">{title}</h3>
                      <p className="mt-1 text-sm text-white/70">{text}</p>
                    </div>
                  </li>
                ))}
              </ul>
            </div>
          </div>
        </section>

        <section id="pricing" className="scroll-mt-20">
          <div className="mx-auto max-w-6xl px-4 py-24 sm:px-6">
            <Heading eyebrow="Pricing" title="Plans priced in rupees" center>
              Start on a trial, and move to a plan when your team is ready.
            </Heading>
            <Suspense fallback={<p className="text-center text-muted-foreground">Loading prices…</p>}>
              <LivePricing />
            </Suspense>
          </div>
        </section>

        <section className="mx-4 mb-16 sm:mx-6 xl:mx-auto xl:max-w-6xl">
          <div className="glass relative overflow-hidden rounded-[2rem] px-6 py-14 text-center sm:px-12">
            <div aria-hidden className="pointer-events-none absolute -bottom-24 left-1/2 -z-10 h-64 w-[40rem] -translate-x-1/2 rounded-full bg-[#9fb2dc]/50 blur-3xl" />
            <h2 className="text-3xl font-bold tracking-tight text-balance text-[#1b2a55] sm:text-4xl">
              Put the inspection register away
            </h2>
            <p className="mx-auto mt-4 max-w-xl text-lg text-muted-foreground">
              Set up your suppliers and first inspection template, and receive your next delivery in QualityOS.
            </p>
            <div className="mt-8 flex flex-wrap justify-center gap-3">
              <Link href="/register" className={buttonVariants({ size: "lg", className: "h-11 px-5" })}>
                Start a trial
                <ArrowRight data-icon="inline-end" />
              </Link>
              <Link href="/docs" className={buttonVariants({ variant: "outline", size: "lg", className: "h-11 px-5" })}>
                Read the docs
              </Link>
            </div>
          </div>
        </section>
      </main>

      <footer className="border-t border-white/70 bg-white/40 backdrop-blur">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-4 px-4 py-8 text-sm text-muted-foreground sm:px-6">
          <div className="flex items-center gap-2 font-semibold text-[#1b2a55]">
            <ShieldCheck aria-hidden className="size-4" />
            QualityOS
          </div>
          <p className="max-w-xl">QualityOS supports your quality system. It does not certify it or confer any regulatory approval.</p>
          <div className="flex gap-5">
            <Link className="hover:text-foreground" href="/docs">Docs</Link>
            <Link className="hover:text-foreground" href="/status">System status</Link>
          </div>
        </div>
      </footer>
    </div>
  );
}
