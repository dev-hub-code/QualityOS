"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { MonthBars, RankBars } from "@/components/analytics/charts";
import { rate } from "@/components/analytics/kpi-tiles";
import { formatMoney } from "@/components/billing/subscription-banner";
import { usePeriod } from "@/components/platform/period-select";
import { PageHeader, QueryState, Section } from "@/components/records/parts";
import { buttonVariants } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { api } from "@/lib/api";
import type { PlatformAnalytics } from "@/lib/types";

const orNone = (value: number | null) => (value === null ? "None yet" : formatMoney(value));

function tiles(data: PlatformAnalytics): [string, string | number][] {
  return [
    ["MRR", formatMoney(data.mrr)],
    ["ARR", formatMoney(data.arr)],
    ["New customers", data.newCustomers],
    ["Average revenue per organisation", orNone(data.averageRevenue)],
    ["Customer churn", `${rate(data.customerChurn)} (${data.lostCustomers} of ${data.openingCustomers})`],
    ["Revenue churn", `${rate(data.revenueChurn)} (${formatMoney(data.revenueLost)} a month)`],
    ["Trial conversion", `${rate(data.trialConversion)} (${data.converted} of ${data.registered})`],
    ["Outstanding receivables", formatMoney(data.outstanding)],
    ["Collected in period", formatMoney(data.collected)],
    ["Payment collection rate", rate(data.collectionRate)],
  ];
}

export default function PlatformAnalyticsPage() {
  const { query, controls } = usePeriod();
  const { data, error } = useQuery({
    queryKey: ["/platform/analytics", query],
    queryFn: () => api.get<PlatformAnalytics>(`/platform/analytics?${query}`),
    placeholderData: keepPreviousData,
  });
  return (
    <div className="space-y-6">
      <PageHeader
        title="Analytics"
        subtitle="All amounts are in rupees, without GST. Recurring figures are as they stood at the end of the period."
      >
        <a className={buttonVariants({ variant: "outline" })} href={`/api/v1/platform/analytics/export?${query}`}>
          Export CSV
        </a>
      </PageHeader>
      {controls}
      {!data ? (
        <QueryState error={error} />
      ) : (
        <>
          <dl className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
            {tiles(data).map(([label, value]) => (
              <Card key={label}>
                <CardContent>
                  <dt className="text-sm text-muted-foreground">{label}</dt>
                  <dd className="text-xl font-semibold tabular-nums">{value}</dd>
                </CardContent>
              </Card>
            ))}
          </dl>
          <div className="grid gap-4 xl:grid-cols-2">
            <Section title="Recurring revenue and collections, by month">
              <MonthBars
                grouped
                data={data.months}
                series={[
                  { key: "mrr", label: "Recurring revenue" },
                  { key: "collected", label: "Collected" },
                ]}
              />
            </Section>
            <Section title="Revenue by plan">
              <RankBars
                headings={["Plan", "Monthly recurring"]}
                rows={data.revenueByPlan.map(({ name, mrr }) => ({ label: name, value: mrr, display: formatMoney(mrr) }))}
              />
            </Section>
            <Section title="Subscriptions by plan">
              <RankBars
                headings={["Plan", "Organisations"]}
                rows={data.plans.map(({ name, count }) => ({ label: name, value: count, display: String(count) }))}
              />
            </Section>
          </div>
        </>
      )}
    </div>
  );
}
