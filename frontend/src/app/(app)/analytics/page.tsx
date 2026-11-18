"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { MonthBars, RankBars, type Rank } from "@/components/analytics/charts";
import Link from "next/link";
import { KpiTiles, ProductionTiles } from "@/components/analytics/kpi-tiles";
import { NO_PERIOD, PeriodFilter, periodQuery } from "@/components/analytics/period-filter";
import { PageHeader, QueryState, Section } from "@/components/records/parts";
import { useMe } from "@/hooks/use-me";
import { figureAccess } from "@/lib/analytics-access";
import { api } from "@/lib/api";
import type { Overview, QualityShare } from "@/lib/types";

const passRates = (shares: QualityShare[]): Rank[] =>
  shares.map((share) => ({
    label: share.name,
    value: share.passRate ?? 0,
    display: `${share.passRate ?? 0}%`,
    note: `${share.passed} of ${share.inspections} passed`,
  }));

export default function AnalyticsPage() {
  const [period, setPeriod] = useState(NO_PERIOD);
  const { data: me } = useMe();
  const access = figureAccess(me?.permissions);
  const path = `/analytics/overview?${periodQuery(period)}`;
  const { data, error } = useQuery({
    queryKey: [path],
    queryFn: () => api.get<Overview>(path),
    placeholderData: keepPreviousData,
    retry: false,
  });
  return (
    <div className="space-y-6">
      <PageHeader title="Analytics" subtitle={data && `${data.from} to ${data.to}`} />
      <PeriodFilter value={period} onChange={setPeriod} />
      {error && <QueryState error={error} />}
      {!data && !error && <QueryState error={null} />}
      {data && (
        <>
          <KpiTiles kpis={data.kpis} all access={access} />
          {data.production && access.production && (
            <Section
              title="Production"
              action={
                <Link className="text-sm text-primary hover:underline" href="/manufacturing/analytics">
                  OEE and output
                </Link>
              }
            >
              <ProductionTiles figures={data.production} access={access} />
            </Section>
          )}
          <div className="grid gap-6 lg:grid-cols-2">
            {data.inspectionTrend && (
              <Section title="Inspection trend">
                <MonthBars
                  data={data.inspectionTrend}
                  series={[
                    { key: "passed", label: "Passed" },
                    { key: "failed", label: "Failed" },
                  ]}
                />
              </Section>
            )}
            {data.inspectionTrend && (
              <Section title="Monthly lot rejections">
                <RankBars
                  headings={["Month", "Lots rejected"]}
                  rows={data.inspectionTrend.filter((month) => month.rejectedLots > 0).map((month) => ({
                    label: month.month,
                    value: month.rejectedLots,
                    display: String(month.rejectedLots),
                  }))}
                />
              </Section>
            )}
            {data.suppliers && (
              <Section title="Supplier scorecards">
                <RankBars
                  max={100}
                  headings={["Supplier", "Lots rejected"]}
                  rows={data.suppliers.map((supplier) => ({
                    label: supplier.name,
                    value: supplier.rejectionRate ?? 0,
                    display: `${supplier.rejectionRate ?? 0}%`,
                    note: `${supplier.rejectedLots} of ${supplier.lots} lots, ${supplier.ncrs} NCRs, ${supplier.openNcrs} open`,
                  }))}
                />
              </Section>
            )}
            {data.defects && (
              <Section title="Defect Pareto">
                <RankBars
                  headings={["Category", "NCRs, share, running total"]}
                  rows={data.defects.map((defect) => ({
                    label: defect.category,
                    value: defect.ncrs,
                    display: `${defect.ncrs} · ${defect.share}% · ${defect.cumulativeShare}%`,
                  }))}
                />
                {data.defects.length > 0 && (
                  <p className="mt-1 text-xs text-muted-foreground">NCRs, share of all, running total.</p>
                )}
              </Section>
            )}
            {data.products && (
              <Section title="Product pass rate">
                <RankBars max={100} headings={["Product", "Pass rate"]} rows={passRates(data.products)} />
              </Section>
            )}
            {data.facilities && (
              <Section title="Facility pass rate">
                <RankBars max={100} headings={["Facility", "Pass rate"]} rows={passRates(data.facilities)} />
              </Section>
            )}
            {data.ncrAgeing && (
              <Section title="Open NCRs by age">
                <RankBars
                  headings={["Age", "Open NCRs"]}
                  rows={data.ncrAgeing.map((bucket) => ({ label: bucket.label, value: bucket.ncrs, display: String(bucket.ncrs) }))}
                />
              </Section>
            )}
            {data.capaTrend && (
              <Section title="CAPAs opened and closed">
                <MonthBars
                  data={data.capaTrend}
                  grouped
                  series={[
                    { key: "opened", label: "Opened" },
                    { key: "closed", label: "Closed" },
                  ]}
                />
              </Section>
            )}
          </div>
        </>
      )}
    </div>
  );
}
