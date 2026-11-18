"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import {
  formatDate,
  formatMoney,
} from "@/components/billing/subscription-banner";
import { ReasonDialog } from "@/components/platform/billing-dialogs";
import { Section } from "@/components/records/parts";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import type { UnappliedPayment } from "@/lib/types";

const PATH = "/platform/unapplied-payments";

/**
 * Payments the gateway took that no invoice could take: the wrong amount or currency, or an invoice already paid.
 * They are never revenue, so they go back whole, through the gateway. One stays listed until Razorpay confirms its
 * refund went through; a failed refund leaves it to refund again.
 */
export function UnappliedPayments() {
  const billing = usePermission("PLATFORM_BILLING_MANAGE");
  const [refunding, setRefunding] = useState<UnappliedPayment | null>(null);
  const { data = [] } = useQuery({
    queryKey: [PATH],
    queryFn: () => api.get<UnappliedPayment[]>(PATH),
  });
  if (data.length === 0) return null;
  return (
    <Section title="Payments waiting for review">
      <ul className="divide-y text-sm">
        {data.map((payment) => (
          <li
            key={payment.orderId}
            className="flex flex-wrap items-center gap-2 py-2"
          >
            <span>
              {payment.organisation}: {formatMoney(payment.amount)} on order{" "}
              {payment.gatewayOrderId} for {payment.invoiceNumber}, since{" "}
              {formatDate(payment.since)}
            </span>
            {payment.status === "REFUND_PENDING" && (
              <span className="text-muted-foreground">
                Refund sent, waiting for Razorpay to confirm it
              </span>
            )}
            {payment.status === "NEEDS_REVIEW" &&
              payment.refundStatus === "FAILED" && (
                <span className="text-destructive">
                  The last refund failed
                  {payment.refundFailure ? `: ${payment.refundFailure}` : ""}
                </span>
              )}
            {billing && payment.status === "NEEDS_REVIEW" && (
              <Button
                size="sm"
                variant="outline"
                onClick={() => setRefunding(payment)}
              >
                {payment.refundStatus === "FAILED" ? "Refund again" : "Refund"}
              </Button>
            )}
          </li>
        ))}
      </ul>
      {refunding && (
        <ReasonDialog
          title={`Refund order ${refunding.gatewayOrderId} in full`}
          path={`/platform/organisations/${refunding.tenantId}/gateway-orders/${refunding.orderId}/refund`}
          onClose={() => setRefunding(null)}
        />
      )}
    </Section>
  );
}
