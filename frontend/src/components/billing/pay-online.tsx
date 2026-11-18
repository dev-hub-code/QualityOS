"use client";

import { useState } from "react";
import { formatMoney } from "@/components/billing/subscription-banner";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";
import { payWithRazorpay } from "@/lib/razorpay-checkout";
import type { Checkout, Invoice } from "@/lib/types";

/**
 * Pays an invoice in Razorpay's checkout, opened over the page. Afterwards {@link onDone} asks the server what
 * happened, whether the person paid or closed it, since a payment can go through after the window is closed.
 */
export function PayOnline({
  invoice,
  testMode,
  onDone,
}: {
  invoice: Invoice;
  testMode: boolean;
  onDone: (orderId: string, outcome: "paid" | "closed") => void;
}) {
  const [error, setError] = useState("");
  const [pending, setPending] = useState(false);

  async function pay() {
    setError("");
    setPending(true);
    try {
      const checkout = await api.post<Checkout>(`/subscription/invoices/${invoice.id}/checkout`, {});
      const outcome = await payWithRazorpay({
        key: checkout.keyId,
        order_id: checkout.gatewayOrderId,
        amount: checkout.amount,
        currency: checkout.currency,
        name: checkout.name,
        description: checkout.description,
        prefill: { name: checkout.customerName, email: checkout.customerEmail },
      });
      onDone(checkout.orderId, outcome);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Something went wrong. Please try again.");
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="mt-3 flex flex-wrap items-center gap-3">
      <Button size="sm" disabled={pending} onClick={pay}>
        {pending ? "Payment open…" : `Pay ${formatMoney(invoice.total)} online`}
      </Button>
      <span className="text-sm text-muted-foreground">UPI, cards, netbanking and wallets, through Razorpay.</span>
      {testMode && <p className="w-full text-sm text-muted-foreground">Test mode: no real money moves.</p>}
      {error && (
        <p role="alert" className="w-full text-sm text-destructive">
          {error}
        </p>
      )}
    </div>
  );
}
