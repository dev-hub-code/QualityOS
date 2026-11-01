"use client";

import { useState } from "react";
import { formatMoney } from "@/components/billing/subscription-banner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { api } from "@/lib/api";
import type { Checkout, Invoice } from "@/lib/types";

const MOBILE = /^[6-9]\d{9}$/;

/** Leaves the page for Cashfree's own checkout, so card and UPI details never reach QualityOS. */
async function openCheckout(invoiceId: string, phone: string) {
  const { paymentSessionId, mode } = await api.post<Checkout>(`/subscription/invoices/${invoiceId}/checkout`, { phone });
  const { load } = await import("@cashfreepayments/cashfree-js");
  const cashfree = await load({ mode });
  await cashfree.checkout({ paymentSessionId, redirectTarget: "_self" });
}

export function PayOnline({ invoice, testMode }: { invoice: Invoice; testMode: boolean }) {
  const [phone, setPhone] = useState("");
  const [error, setError] = useState("");
  const [pending, setPending] = useState(false);

  async function pay() {
    if (!MOBILE.test(phone)) {
      setError("Enter a 10-digit mobile number.");
      return;
    }
    setError("");
    setPending(true);
    try {
      await openCheckout(invoice.id, phone);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Something went wrong. Please try again.");
      setPending(false);
    }
  }

  return (
    <div className="mt-3 flex flex-wrap items-end gap-3">
      <div className="space-y-1">
        <Label htmlFor="payment-phone">Mobile number</Label>
        <Input
          id="payment-phone"
          inputMode="numeric"
          autoComplete="tel-national"
          maxLength={10}
          className="w-40 bg-background"
          value={phone}
          onChange={(event) => setPhone(event.target.value)}
        />
      </div>
      <Button size="sm" disabled={pending} onClick={pay}>
        {pending ? "Opening payment…" : `Pay ${formatMoney(invoice.total)} online`}
      </Button>
      {testMode && <p className="text-sm text-muted-foreground">Test mode: no real money moves.</p>}
      {error && <p role="alert" className="w-full text-sm text-destructive">{error}</p>}
    </div>
  );
}
