"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { formatDate, formatMoney } from "@/components/billing/subscription-banner";
import { PageHeader, QueryState, Section, StatusBadge, useAction } from "@/components/records/parts";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { api } from "@/lib/api";
import { storageText, usageText } from "@/lib/usage";
import type { BillingCycle, Invoice, Payment, Plan, Subscription } from "@/lib/types";

const STATE: Record<Subscription["state"], string> = {
  TRIAL: "Trial",
  ACTIVE: "Active",
  GRACE: "Ended, grace period",
  READ_ONLY: "Read-only",
};

function limit(used: number, max: number | null) {
  return max === null ? `${used}, no limit` : `${used} of ${max}`;
}

function PlanCard({
  plan,
  cycle,
  current,
  onChoose,
  pending,
}: {
  plan: Plan;
  cycle: BillingCycle;
  current: boolean;
  onChoose: () => void;
  pending: boolean;
}) {
  const price = cycle === "ANNUAL" ? plan.annualPrice : plan.monthlyPrice;
  return (
    <div className="flex flex-col gap-2 rounded-lg border p-4">
      <h3 className="font-medium">{plan.name}</h3>
      <p className="text-sm text-muted-foreground">{plan.description}</p>
      <p className="text-xl font-semibold">
        {price === null ? "Custom" : formatMoney(price)}
        {price !== null && (
          <span className="text-sm font-normal text-muted-foreground"> a {cycle === "ANNUAL" ? "year" : "month"}, plus GST</span>
        )}
      </p>
      <p className="text-sm text-muted-foreground">
        {plan.maxUsers ?? "Unlimited"} users, {plan.maxFacilities ?? "unlimited"} facilities
      </p>
      <div className="mt-auto pt-2">
        {price === null ? (
          <p className="text-sm text-muted-foreground">Contact us to agree this plan.</p>
        ) : (
          <Button size="sm" variant={current ? "default" : "outline"} disabled={pending} onClick={onChoose}>
            {current ? "Renew" : `Choose ${plan.name}`}
          </Button>
        )}
      </div>
    </div>
  );
}

export default function BillingPage() {
  const { run, pending, message } = useAction();
  const [cycle, setCycle] = useState<BillingCycle>("MONTHLY");
  const get = <T,>(path: string) => ({ queryKey: [path], queryFn: () => api.get<T>(path) });
  const subscription = useQuery(get<Subscription>("/subscription"));
  const plans = useQuery(get<Plan[]>("/subscription/plans"));
  const invoices = useQuery(get<Invoice[]>("/subscription/invoices"));
  const payments = useQuery(get<Payment[]>("/subscription/payments"));
  const current = subscription.data;
  if (!current) return <QueryState error={subscription.error} />;
  const number = (invoiceId: string) => invoices.data?.find((invoice) => invoice.id === invoiceId)?.invoiceNumber;
  const open = invoices.data?.find((invoice) => invoice.status === "ISSUED");
  return (
    <div className="space-y-6">
      <PageHeader title="Billing" subtitle={`${current.planName} plan`} />
      {message && <p className="text-sm text-destructive">{message}</p>}
      <Section title="Subscription">
        <dl className="grid gap-3 text-sm sm:grid-cols-2 lg:grid-cols-4">
          <div>
            <dt className="text-muted-foreground">Status</dt>
            <dd>{STATE[current.state]}</dd>
          </div>
          <div>
            <dt className="text-muted-foreground">{current.cancelledAt ? "Ends" : "Renews or ends"}</dt>
            <dd>{formatDate(current.endsAt)}</dd>
          </div>
          <div>
            <dt className="text-muted-foreground">Users, with pending invitations</dt>
            <dd>{limit(current.users, current.maxUsers)}</dd>
          </div>
          <div>
            <dt className="text-muted-foreground">Facilities</dt>
            <dd>{limit(current.facilities, current.maxFacilities)}</dd>
          </div>
          <div>
            <dt className="text-muted-foreground">Storage</dt>
            <dd>{storageText(current.storageBytes, current.maxStorageMb)}</dd>
          </div>
          <div>
            <dt className="text-muted-foreground">Inspections this month</dt>
            <dd>{usageText(current.inspectionsThisMonth, current.maxMonthlyInspections)}</dd>
          </div>
          <div>
            <dt className="text-muted-foreground">Attachments</dt>
            <dd>{usageText(current.attachments, current.maxAttachments)}</dd>
          </div>
        </dl>
        <div className="mt-4">
          {current.cancelledAt ? (
            <>
              <p className="mb-2 text-sm text-muted-foreground">Cancelled. It runs until the date above.</p>
              <Button size="sm" variant="outline" disabled={pending} onClick={() => run(() => api.post("/subscription/resume"))}>
                Keep subscription
              </Button>
            </>
          ) : (
            <Button size="sm" variant="outline" disabled={pending} onClick={() => run(() => api.post("/subscription/cancel"))}>
              Cancel subscription
            </Button>
          )}
        </div>
      </Section>
      {open && (
        <p role="status" className="rounded-md border bg-muted px-3 py-2 text-sm">
          Invoice {open.invoiceNumber} for {formatMoney(open.total)} is due by {open.dueOn}. The {open.planName} plan starts
          once it is paid. Choosing another plan replaces this invoice.
        </p>
      )}
      <Section title="Plans">
        <fieldset className="mb-4 flex gap-4 text-sm">
          <legend className="sr-only">Billing period</legend>
          {(["MONTHLY", "ANNUAL"] as const).map((value) => (
            <label key={value} className="flex items-center gap-1.5">
              <input type="radio" name="cycle" checked={cycle === value} onChange={() => setCycle(value)} />
              {value === "MONTHLY" ? "Monthly" : "Annual"}
            </label>
          ))}
        </fieldset>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {plans.data?.map((plan) => (
            <PlanCard
              key={plan.id}
              plan={plan}
              cycle={cycle}
              current={plan.code === current.planCode}
              pending={pending}
              onChoose={() => run(() => api.post("/subscription/plan", { planId: plan.id, cycle }))}
            />
          ))}
        </div>
      </Section>
      <Section title="Invoices">
        {invoices.data?.length === 0 ? (
          <p className="text-sm text-muted-foreground">No invoices yet.</p>
        ) : (
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Invoice</TableHead>
                  <TableHead>Plan</TableHead>
                  <TableHead>Issued</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead className="text-right">Total</TableHead>
                  <TableHead />
                </TableRow>
              </TableHeader>
              <TableBody>
                {invoices.data?.map((invoice) => (
                  <TableRow key={invoice.id}>
                    <TableCell>{invoice.invoiceNumber}</TableCell>
                    <TableCell>{invoice.planName}</TableCell>
                    <TableCell>{invoice.issuedOn}</TableCell>
                    <TableCell>
                      <StatusBadge status={invoice.status} />
                    </TableCell>
                    <TableCell className="text-right">{formatMoney(invoice.total)}</TableCell>
                    <TableCell>
                      <a className="underline" href={`/api/v1/subscription/invoices/${invoice.id}/pdf`}>
                        PDF
                      </a>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </Section>
      <Section title="Payment history">
        {payments.data?.length === 0 ? (
          <p className="text-sm text-muted-foreground">No payments yet.</p>
        ) : (
          <ul className="divide-y text-sm">
            {payments.data?.map((payment) => (
              <li key={payment.id} className="flex flex-wrap items-center gap-2 py-1.5">
                <span>{formatDate(payment.occurredAt)}</span>
                <span>{formatMoney(payment.amount)}</span>
                <StatusBadge status={payment.status} />
                <span className="text-muted-foreground">
                  {number(payment.invoiceId)}, {payment.providerReference}
                  {payment.failureReason && `: ${payment.failureReason}`}
                </span>
              </li>
            ))}
          </ul>
        )}
      </Section>
    </div>
  );
}
