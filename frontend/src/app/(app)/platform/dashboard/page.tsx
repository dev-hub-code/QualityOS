"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";
import {
  Building2,
  CalendarX,
  CircleAlert,
  CircleCheck,
  FileText,
  Hourglass,
  LifeBuoy,
  Lock,
  Percent,
  ReceiptIndianRupee,
  TrendingDown,
  Wallet,
} from "lucide-react";
import { MonthBars, RankBars } from "@/components/analytics/charts";
import { rate } from "@/components/analytics/kpi-tiles";
import { DashboardFrame, FloatCard, HeroCard, HeroHeading, Panel, Pending, Tile, type QuickAction } from "@/components/app/showcase";
import { formatMoney } from "@/components/billing/subscription-banner";
import { usePeriod } from "@/components/platform/period-select";
import { QueryState } from "@/components/records/parts";
import { api } from "@/lib/api";
import type { PlatformDashboard } from "@/lib/types";

const ACTIONS: QuickAction[] = [
  { label: "Customers", href: "/platform/organisations", icon: Building2 },
  { label: "Invoices", href: "/platform/invoices", icon: FileText },
  { label: "Payments", href: "/platform/payments", icon: Wallet },
  { label: "Support", href: "/platform/support", icon: LifeBuoy },
];

export default function PlatformDashboardPage() {
  const { query, controls } = usePeriod();
  const { data, error } = useQuery({
    queryKey: ["/platform/dashboard", query],
    queryFn: () => api.get<PlatformDashboard>(`/platform/dashboard?${query}`),
    placeholderData: keepPreviousData,
  });
  return (
    <DashboardFrame>
      <div className="grid items-center gap-10 lg:grid-cols-[minmax(0,1fr)_31rem]">
        <HeroHeading badge="Control Center" lead="Platform" accent="dashboard">
          <p className="max-w-lg text-base text-slate-600 sm:text-lg">
            Recurring revenue is what is under contract. Collected is money received.
          </p>
          {controls}
          {data && (
            <div className="grid gap-3 pt-2 sm:grid-cols-3">
              <Pending icon={Hourglass} label="On trial" value={data.trials} href="/platform/trials" />
              <Pending icon={CircleAlert} label="In grace period" value={data.grace} href="/platform/subscriptions" />
              <Pending icon={FileText} label="Unpaid invoices" value={data.outstandingInvoices} href="/platform/invoices" />
            </div>
          )}
        </HeroHeading>
        {data && (
          <HeroCard
            brand="Control Center"
            badge={`${data.active} paying`}
            label={<span title="Monthly recurring revenue">MRR</span>}
            value={formatMoney(data.mrr)}
            note={`ARR ${formatMoney(data.arr)}`}
            actions={ACTIONS}
            floats={
              <>
                <FloatCard
                  icon={ReceiptIndianRupee}
                  label="Collected"
                  value={formatMoney(data.collected)}
                  note="in this period"
                  href="/platform/payments"
                />
                <FloatCard
                  icon={Percent}
                  label="Collection rate"
                  value={rate(data.collectionRate)}
                  note={`${formatMoney(data.outstanding)} outstanding`}
                  href="/platform/invoices"
                  alert={data.outstandingInvoices > 0}
                />
              </>
            }
          />
        )}
      </div>

      {!data ? (
        <div className="mt-6">
          <QueryState error={error} />
        </div>
      ) : (
        <>
          {error && <QueryState error={error} />}
          <dl className="mt-10 grid grid-cols-2 gap-3 lg:grid-cols-4">
            <Tile icon={Building2} label="Organisations" value={data.organisations} />
            <Tile icon={CircleCheck} label="Active subscriptions" value={data.active} />
            <Tile icon={Hourglass} label="Trials" value={data.trials} />
            <Tile icon={TrendingDown} label="Churn rate" value={rate(data.churnRate)} />
            <Tile icon={CircleAlert} label="In grace period" value={data.grace} />
            <Tile icon={Lock} label="Read-only" value={data.readOnly} />
            <Tile icon={CalendarX} label="Expired" value={data.expired} />
            <Tile
              icon={FileText}
              label="Outstanding invoices"
              value={`${formatMoney(data.outstanding)} (${data.outstandingInvoices})`}
            />
          </dl>
          <div className="mt-6 grid gap-6 xl:grid-cols-2">
            <Panel title="Recurring revenue and collections, by month">
              <MonthBars
                grouped
                data={data.months}
                series={[
                  { key: "mrr", label: "Recurring revenue" },
                  { key: "collected", label: "Collected" },
                ]}
              />
            </Panel>
            <Panel title="Registrations and trial conversion, by month">
              <MonthBars
                grouped
                data={data.months}
                series={[
                  { key: "registered", label: "Registered" },
                  { key: "converted", label: "Became paying" },
                ]}
              />
            </Panel>
            <Panel title="Paying subscriptions and cancellations, by month">
              <MonthBars
                grouped
                data={data.months}
                series={[
                  { key: "paying", label: "Paying" },
                  { key: "cancelled", label: "Cancelled" },
                ]}
              />
            </Panel>
            <Panel title="Subscriptions by plan">
              <RankBars
                headings={["Plan", "Organisations"]}
                rows={data.plans.map((plan) => ({ label: plan.name, value: plan.count, display: String(plan.count) }))}
              />
            </Panel>
          </div>
        </>
      )}
    </DashboardFrame>
  );
}
