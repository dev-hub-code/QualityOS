"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import type { Subscription } from "@/lib/types";

const WARNING_DAYS = 7;
const DAY = 86_400_000;

export const formatDate = (instant: string) =>
  new Date(instant).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" });

export const formatMoney = (value: number) =>
  `₹${value.toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

export function subscriptionNotice(subscription: Subscription, now = Date.now()): string | null {
  const ends = formatDate(subscription.endsAt);
  if (subscription.state === "READ_ONLY") {
    return "This organisation is read-only because its subscription has ended. All records are safe and can be read and exported.";
  }
  if (subscription.state === "GRACE") {
    return `The subscription has ended. This organisation turns read-only on ${formatDate(subscription.readOnlyFrom)}.`;
  }
  if (new Date(subscription.endsAt).getTime() - now > WARNING_DAYS * DAY) return null;
  return subscription.state === "TRIAL" ? `The trial ends on ${ends}.` : `The subscription ends on ${ends}.`;
}

export function SubscriptionBanner() {
  const canManage = usePermission("SUBSCRIPTION_MANAGE");
  const { data } = useQuery({ queryKey: ["/subscription"], queryFn: () => api.get<Subscription>("/subscription") });
  const notice = data && subscriptionNotice(data);
  if (!notice) return null;
  return (
    <p role="status" className="mb-4 rounded-md border bg-muted px-3 py-2 text-sm">
      {notice}{" "}
      {canManage ? (
        <Link className="font-medium underline" href="/settings/billing">
          Manage billing
        </Link>
      ) : (
        "Ask your organisation owner to renew."
      )}
    </p>
  );
}
