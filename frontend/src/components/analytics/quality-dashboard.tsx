"use client";

import { useQuery } from "@tanstack/react-query";
import { KpiTiles } from "@/components/analytics/kpi-tiles";
import { MonthBars, RankBars } from "@/components/analytics/charts";
import { QueryState, RecordLink, Section, StatusBadge } from "@/components/records/parts";
import { api } from "@/lib/api";
import type { Overview } from "@/lib/types";

const INSPECTIONS: [{ key: string; label: string }, { key: string; label: string }] = [
  { key: "passed", label: "Passed" },
  { key: "failed", label: "Failed" },
];

/** Opening page: the last 90 days at a glance, what is waiting, and what just happened. */
export function QualityDashboard() {
  const path = "/analytics/overview";
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<Overview>(path) });
  if (!data) return <QueryState error={error} />;
  const { pending } = data;
  return (
    <div className="space-y-6">
      <p className="text-sm text-muted-foreground">
        {data.from} to {data.to}. <RecordLink href="/analytics">Full analytics</RecordLink>
      </p>
      <KpiTiles kpis={data.kpis} />
      <div className="grid gap-6 lg:grid-cols-2">
        <Section title="Inspection trend">
          <MonthBars data={data.inspectionTrend} series={INSPECTIONS} />
        </Section>
        <Section title="Waiting for approval">
          <ul className="space-y-1 text-sm">
            <li>
              <RecordLink href="/inspections">Inspections</RecordLink>: {pending.inspectionsAwaitingApproval}
            </li>
            <li>
              <RecordLink href="/ncrs">NCRs ready for closure</RecordLink>: {pending.ncrsAwaitingClosure}
            </li>
            <li>
              <RecordLink href="/audits">Audits ready for closure</RecordLink>: {pending.auditsAwaitingClosure}
            </li>
          </ul>
        </Section>
        <Section title="Supplier lot rejection">
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
        </Section>
        <Section title="Defect categories">
          <RankBars
            headings={["Category", "NCRs"]}
            rows={data.defects.map((defect) => ({ label: defect.category, value: defect.ncrs, display: String(defect.ncrs) }))}
          />
        </Section>
      </div>
      <Section title="Recent activity">
        {data.recent.length === 0 ? (
          <p className="text-sm text-muted-foreground">Nothing yet.</p>
        ) : (
          <ul className="divide-y text-sm">
            {data.recent.map((item) => (
              <li key={item.reference} className="flex flex-wrap items-center gap-2 py-1.5">
                <RecordLink href={item.link}>{item.reference}</RecordLink>
                <StatusBadge status={item.status} />
                <span className="text-muted-foreground">
                  {item.kind}, {item.on}
                </span>
              </li>
            ))}
          </ul>
        )}
      </Section>
    </div>
  );
}
