"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { MonthBars, RankBars } from "@/components/analytics/charts";
import { rate } from "@/components/analytics/kpi-tiles";
import { formatMoney } from "@/components/billing/subscription-banner";
import { PageHeader, QueryState, Section } from "@/components/records/parts";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { api } from "@/lib/api";
import { presetPeriod, PERIODS, type PeriodName } from "@/lib/platform-period";
import type { PlatformDashboard } from "@/lib/types";

export default function PlatformDashboardPage() {
  const [preset, setPreset] = useState<PeriodName | "CUSTOM">("LAST_30");
  const [custom, setCustom] = useState(() => presetPeriod("LAST_30"));
  const period = preset === "CUSTOM" ? custom : presetPeriod(preset);
  const query = new URLSearchParams(period).toString();
  const { data, error } = useQuery({
    queryKey: ["/platform/dashboard", query],
    queryFn: () => api.get<PlatformDashboard>(`/platform/dashboard?${query}`),
    placeholderData: keepPreviousData,
  });
  return (
    <div className="space-y-6">
      <PageHeader title="Dashboard" subtitle="Recurring revenue is what is under contract. Collected is money received." />
      <div className="flex flex-wrap items-end gap-3">
        <div className="space-y-1.5">
          <Label htmlFor="platform-period">Period</Label>
          <select
            id="platform-period"
            className="h-9 rounded-md border bg-background px-2 text-sm"
            value={preset}
            onChange={(event) => setPreset(event.target.value as PeriodName | "CUSTOM")}
          >
            {PERIODS.map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
            <option value="CUSTOM">Custom dates</option>
          </select>
        </div>
        {preset === "CUSTOM" && (
          <>
            <div className="space-y-1.5">
              <Label htmlFor="platform-from">From</Label>
              <Input
                id="platform-from"
                type="date"
                value={custom.from}
                onChange={(event) => setCustom({ ...custom, from: event.target.value })}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="platform-to">To</Label>
              <Input
                id="platform-to"
                type="date"
                value={custom.to}
                onChange={(event) => setCustom({ ...custom, to: event.target.value })}
              />
            </div>
          </>
        )}
      </div>
      {!data ? (
        <QueryState error={error} />
      ) : (
        <>
          {error && <QueryState error={error} />}
          <dl className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-4">
            {tiles(data).map(([label, value]) => (
              <Card key={label}>
                <CardContent>
                  <dt className="text-sm text-muted-foreground">{label}</dt>
                  <dd className="text-2xl font-semibold tabular-nums">{value}</dd>
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
            <Section title="Registrations and trial conversion, by month">
              <MonthBars
                grouped
                data={data.months}
                series={[
                  { key: "registered", label: "Registered" },
                  { key: "converted", label: "Became paying" },
                ]}
              />
            </Section>
            <Section title="Paying subscriptions and cancellations, by month">
              <MonthBars
                grouped
                data={data.months}
                series={[
                  { key: "paying", label: "Paying" },
                  { key: "cancelled", label: "Cancelled" },
                ]}
              />
            </Section>
            <Section title="Subscriptions by plan">
              <RankBars
                headings={["Plan", "Organisations"]}
                rows={data.plans.map((plan) => ({ label: plan.name, value: plan.count, display: String(plan.count) }))}
              />
            </Section>
          </div>
        </>
      )}
    </div>
  );
}

function tiles(data: PlatformDashboard): [string, string | number][] {
  return [
    ["Organisations", data.organisations],
    ["Active subscriptions", data.active],
    ["Trials", data.trials],
    ["Expired", data.expired],
    ["In grace period", data.grace],
    ["Read-only", data.readOnly],
    ["MRR", formatMoney(data.mrr)],
    ["ARR", formatMoney(data.arr)],
    ["Outstanding invoices", `${formatMoney(data.outstanding)} (${data.outstandingInvoices})`],
    ["Collected in period", formatMoney(data.collected)],
    ["Collection rate", rate(data.collectionRate)],
    ["Churn rate", rate(data.churnRate)],
  ];
}
