"use client";

import { formatDate } from "@/components/billing/subscription-banner";
import { SubscriptionTable, type SubscriptionRow } from "@/components/platform/subscription-table";
import { PageHeader } from "@/components/records/parts";

const STATES: [string, string][] = [
  ["TRIAL", "Trial"],
  ["ACTIVE", "Active"],
  ["GRACE", "Grace"],
  ["READ_ONLY", "Read-only"],
  ["CANCELLED", "Cancelled"],
  ["SUSPENDED", "Suspended"],
];

const status = ({ subscription, suspendedAt }: SubscriptionRow) => {
  if (suspendedAt) return "suspended";
  const state = subscription.state.replace("_", " ").toLowerCase();
  return subscription.cancelledAt ? `${state}, cancelled` : state;
};

const payment = ({ openInvoices, nextDue }: SubscriptionRow) => {
  if (openInvoices.length === 0) return "Nothing due";
  return `Due ${formatDate(nextDue ?? openInvoices[0].dueOn)}`;
};

export default function PlatformSubscriptionsPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Subscriptions"
        subtitle="Open an organisation to extend, cancel, resume or suspend. A plan changes or renews when its invoice is paid."
      />
      <SubscriptionTable
        stateOptions={STATES}
        empty="No subscriptions match."
        columns={[
          ["Subscription ID", (row) => <code className="text-xs">{row.subscriptionId.slice(0, 8)}</code>],
          ["Plan", (row) => row.subscription.planName],
          ["Billing", (row) => row.subscription.billingCycle?.toLowerCase() ?? "Not yet billed"],
          ["Status", status],
          ["Started", (row) => formatDate(row.registeredAt)],
          ["Renews or ends", (row) => formatDate(row.subscription.endsAt)],
          ["Payment", payment],
        ]}
      />
    </div>
  );
}
