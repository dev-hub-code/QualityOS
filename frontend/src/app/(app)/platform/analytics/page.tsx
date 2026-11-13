"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";
import {
  Building2,
  CalendarRange,
  FileText,
  Hourglass,
  Percent,
  TrendingDown,
  TrendingUp,
  UserMinus,
  UserPlus,
  Wallet,
  type LucideIcon,
} from "lucide-react";
import { Tile } from "@/components/app/showcase";
import { MonthBars, RankBars } from "@/components/analytics/charts";
import { rate } from "@/components/analytics/kpi-tiles";
import { formatMoney } from "@/components/billing/subscription-banner";
import { usePeriod } from "@/components/platform/period-select";
import { PageHeader, QueryState, Section } from "@/components/records/parts";
import { buttonVariants } from "@/components/ui/button";
import { api } from "@/lib/api";
import type { PlatformAnalytics } from "@/lib/types";

const orNone = (value: number | null) => (value === null ? "None yet" : formatMoney(value));

function tiles(data: PlatformAnalytics): [string, string | number, LucideIcon][] {
  return [
    ["MRR", formatMoney(data.mrr), TrendingUp],
    ["ARR", formatMoney(data.arr), CalendarRange],
    ["New customers", data.newCustomers, UserPlus],
    ["Average revenue per organisation", orNone(data.averageRevenue), Building2],
    ["Customer churn", `${rate(data.customerChurn)} (${data.lostCustomers} of ${data.openingCustomers})`, UserMinus],
    ["Revenue churn", `${rate(data.revenueChurn)} (${formatMoney(data.revenueLost)} a month)`, TrendingDown],
    ["Trial conversion", `${rate(data.trialConversion)} (${data.converted} of ${data.registered})`, Hourglass],
    ["Outstanding receivables", formatMoney(data.outstanding), FileText],
    ["Collected in period", formatMoney(data.collected), Wallet],
    ["Payment collection rate", rate(data.collectionRate), Percent],
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
            {tiles(data).map(([label, value, icon]) => (
              <Tile key={label} icon={icon} label={label} value={value} />
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
