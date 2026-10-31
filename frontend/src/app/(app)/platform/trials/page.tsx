"use client";

import { useQuery } from "@tanstack/react-query";
import { formatDate } from "@/components/billing/subscription-banner";
import { daysLeft, SubscriptionTable } from "@/components/platform/subscription-table";
import { PageHeader, QueryState } from "@/components/records/parts";
import { Card, CardContent } from "@/components/ui/card";
import { api } from "@/lib/api";
import type { TrialSummary } from "@/lib/types";

function Figure({ label, value }: { label: string; value: string | number }) {
  return (
    <Card>
      <CardContent>
        <p className="text-sm text-muted-foreground">{label}</p>
        <p className="mt-1 text-2xl font-semibold tabular-nums">{value}</p>
      </CardContent>
    </Card>
  );
}

export default function PlatformTrialsPage() {
  const path = "/platform/trials/summary";
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<TrialSummary>(path) });
  return (
    <div className="space-y-6">
      <PageHeader
        title="Trials"
        subtitle="Open an organisation to extend or end its trial. To convert one, raise an invoice and record its payment."
      />
      {data ? (
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
          <Figure label="Ending in 3 days" value={data.endingIn3Days} />
          <Figure label="Ending in 7 days" value={data.endingIn7Days} />
          <Figure label="Converted this month" value={data.convertedThisMonth} />
          <Figure label="Conversion rate" value={data.conversionRate === null ? "None yet" : `${data.conversionRate}%`} />
        </div>
      ) : (
        <QueryState error={error} />
      )}
      <SubscriptionTable
        state="TRIAL"
        empty="No trials are running."
        columns={[
          ["Plan", (row) => row.subscription.planName],
          ["Started", (row) => formatDate(row.registeredAt)],
          ["Ends", (row) => formatDate(row.subscription.endsAt)],
          ["Days left", (row) => daysLeft(row.subscription.endsAt)],
          ["Conversion", (row) => (row.openInvoices.length > 0 ? "Invoice raised" : "Not started")],
        ]}
      />
    </div>
  );
}
