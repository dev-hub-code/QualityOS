"use client";

import type { ReactNode } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import {
  ArrowRight,
  ArrowUpRight,
  ClipboardCheck,
  FileWarning,
  GitBranch,
  ListChecks,
  ScanSearch,
  ShieldAlert,
  ShieldCheck,
  TimerReset,
  Wrench,
} from "lucide-react";
import { rate } from "@/components/analytics/kpi-tiles";
import { MonthBars, RankBars } from "@/components/analytics/charts";
import { QueryState, RecordLink, StatusBadge } from "@/components/records/parts";
import { DashboardFrame, FloatCard, HeroCard, HeroHeading, Panel, Pending, Tile, type QuickAction } from "@/components/app/showcase";
import { api } from "@/lib/api";
import type { Overview } from "@/lib/types";

const INSPECTIONS: [{ key: string; label: string }, { key: string; label: string }] = [
  { key: "passed", label: "Passed" },
  { key: "failed", label: "Failed" },
];

const ACTIONS: QuickAction[] = [
  { label: "Inspect", href: "/inspections", icon: ClipboardCheck },
  { label: "NCRs", href: "/ncrs", icon: FileWarning },
  { label: "CAPAs", href: "/capas", icon: Wrench },
  { label: "Trace", href: "/traceability", icon: GitBranch },
];

function day(iso: string) {
  return new Date(`${iso}T00:00:00`).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" });
}

export function Welcome({ name, children }: { name?: string; children?: ReactNode }) {
  const first = name?.split(" ")[0];
  return (
    <HeroHeading badge="Quality overview" lead={first ? "Welcome back," : "Welcome back"} accent={first}>
      {children}
    </HeroHeading>
  );
}

function PassRateCard({ data }: { data: Overview }) {
  const { kpis } = data;
  return (
    <HeroCard
      brand="QualityOS"
      badge="90 days"
      label="Inspection pass rate"
      value={rate(kpis.inspectionPassRate)}
      note={`${kpis.passedInspections} passed, ${kpis.failedInspections} failed`}
      actions={ACTIONS}
      floats={
        <>
          <FloatCard icon={FileWarning} label="Open NCRs" value={kpis.openNcrs} note={`${kpis.closedNcrs} closed`} href="/ncrs" />
          <FloatCard
            icon={TimerReset}
            label="Overdue CAPAs"
            value={kpis.overdueCapas}
            note={`${kpis.openCapas} open`}
            href="/capas"
            alert={kpis.overdueCapas > 0}
          />
        </>
      }
    />
  );
}

/** Opening page: the last 90 days at a glance, what is waiting, and what just happened. */
export function QualityDashboard({ name }: { name?: string }) {
  const path = "/analytics/overview";
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<Overview>(path) });
  if (!data) {
    return (
      <DashboardFrame>
        <Welcome name={name} />
        <div className="mt-6">
          <QueryState error={error} />
        </div>
      </DashboardFrame>
    );
  }
  const { pending, kpis } = data;
  return (
    <DashboardFrame>
      <div className="grid items-center gap-10 lg:grid-cols-[minmax(0,1fr)_31rem]">
        <Welcome name={name}>
          <p className="max-w-lg text-base text-slate-600 sm:text-lg">
            Here is how quality moved from {day(data.from)} to {day(data.to)}, and what is waiting on your team.
          </p>
          <div className="flex flex-wrap items-center gap-4">
            <Link
              href="/analytics"
              className="inline-flex items-center gap-2 rounded-xl bg-gradient-to-b from-[#2f4590] to-[#1c2a62] px-5 py-2.5 text-sm font-medium text-white shadow-[0_10px_24px_-10px_rgba(28,42,98,0.8)] transition hover:brightness-110"
            >
              Full analytics <ArrowRight className="size-4" />
            </Link>
            <Link href="/reports" className="group inline-flex items-center gap-2 text-sm font-medium text-[#1b2a55]">
              <span className="grid size-9 place-items-center rounded-full bg-white/80 shadow ring-1 ring-white transition group-hover:bg-white">
                <ArrowUpRight className="size-4" />
              </span>
              Reports
            </Link>
          </div>
          <div className="grid gap-3 pt-2 sm:grid-cols-3">
            <Pending icon={ClipboardCheck} label="Inspections to approve" value={pending.inspectionsAwaitingApproval} href="/inspections" />
            <Pending icon={ListChecks} label="NCRs ready to close" value={pending.ncrsAwaitingClosure} href="/ncrs" />
            <Pending icon={ScanSearch} label="Audits ready to close" value={pending.auditsAwaitingClosure} href="/audits" />
          </div>
        </Welcome>
        <PassRateCard data={data} />
      </div>

      <dl className="mt-10 grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Tile icon={ClipboardCheck} label="Inspections decided" value={kpis.totalInspections} />
        <Tile icon={ShieldCheck} label="First pass yield" value={rate(kpis.firstPassYield)} />
        <Tile icon={FileWarning} label="Open NCRs" value={kpis.openNcrs} />
        <Tile icon={ShieldAlert} label="Overdue CAPAs" value={kpis.overdueCapas} />
      </dl>

      <div className="mt-6 grid gap-6 lg:grid-cols-2">
        <Panel title="Inspection trend">
          <MonthBars data={data.inspectionTrend} series={INSPECTIONS} />
        </Panel>
        <Panel title="Recent activity">
          {data.recent.length === 0 ? (
            <p className="text-sm text-muted-foreground">Nothing yet.</p>
          ) : (
            <ul className="space-y-2 text-sm">
              {data.recent.map((item) => (
                <li key={item.reference} className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-xl bg-white/60 px-3 py-2 ring-1 ring-white/80">
                  <RecordLink href={item.link}>{item.reference}</RecordLink>
                  <StatusBadge status={item.status} />
                  <span className="ml-auto text-xs text-slate-500">
                    {item.kind}, {item.on}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </Panel>
        <Panel title="Supplier lot rejection">
          <RankBars
            max={100}
            headings={["Supplier", "Lots rejected"]}
            rows={data.suppliers.map((supplier) => ({
              label: supplier.name,
              value: supplier.rejectionRate ?? 0,
              display: `${supplier.rejectionRate ?? 0}%`,
              note: `${supplier.rejectedLots} of ${supplier.lots} lots`,
            }))}
          />
        </Panel>
        <Panel title="Defect categories">
          <RankBars
            headings={["Category", "NCRs"]}
            rows={data.defects.map((defect) => ({ label: defect.category, value: defect.ncrs, display: String(defect.ncrs) }))}
          />
        </Panel>
      </div>
    </DashboardFrame>
  );
}
