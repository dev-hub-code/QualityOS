"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { formatDate, formatMoney } from "@/components/billing/subscription-banner";
import { PageHeader, QueryState, useAction, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { api } from "@/lib/api";
import type { FieldDef } from "@/lib/record-form";
import type { Invoice, OrganisationList, Plan } from "@/lib/types";

const PAYMENT: FieldDef[] = [
  { name: "reference", label: "Bank or UTR reference", required: true },
  { name: "amount", label: "Amount received", type: "decimal", required: true },
];
const EXTEND: FieldDef[] = [{ name: "days", label: "Extra days", type: "decimal", required: true }];

interface Paying {
  tenantId: string;
  invoice: Invoice;
}

export default function PlatformOrganisationsPage() {
  const refresh = useRefresh();
  const { pending, message } = useAction();
  const [paying, setPaying] = useState<Paying | null>(null);
  const [extending, setExtending] = useState<string | null>(null);
  const [invoicing, setInvoicing] = useState<string | null>(null);
  const path = "/platform/organisations";
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<OrganisationList>(path) });
  const plans = useQuery({ queryKey: ["/platform/plans"], queryFn: () => api.get<Plan[]>("/platform/plans") });
  if (!data) return <QueryState error={error} />;
  const invoiceFields: FieldDef[] = [
    {
      name: "planId",
      label: "Plan",
      type: "select",
      required: true,
      options: (plans.data ?? []).map((plan) => ({ value: plan.id, label: plan.name })),
    },
    {
      name: "cycle",
      label: "Billing period",
      type: "select",
      required: true,
      options: [
        { value: "MONTHLY", label: "Monthly" },
        { value: "ANNUAL", label: "Annual" },
      ],
    },
    { name: "amount", label: "Amount before GST", type: "decimal", required: true },
  ];
  return (
    <div className="space-y-6">
      <PageHeader title="Organisations" subtitle="Everything done here shows in that organisation's audit log." />
      {message && <p className="text-sm text-destructive">{message}</p>}
      {data.total > data.items.length && (
        <p role="status" className="text-sm">
          Showing {data.items.length} of {data.total}, with unpaid invoices first and then the soonest to end.
        </p>
      )}
      <div className="overflow-x-auto">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Organisation</TableHead>
              <TableHead>Plan</TableHead>
              <TableHead>State</TableHead>
              <TableHead>Ends</TableHead>
              <TableHead>Open invoice</TableHead>
              <TableHead />
            </TableRow>
          </TableHeader>
          <TableBody>
            {data.items.map(({ tenantId, organizationName, subscription, openInvoices }) => (
              <TableRow key={tenantId}>
                <TableCell>{organizationName}</TableCell>
                <TableCell>{subscription.planName}</TableCell>
                <TableCell>{subscription.state.replace("_", " ").toLowerCase()}</TableCell>
                <TableCell>{formatDate(subscription.endsAt)}</TableCell>
                <TableCell>
                  {openInvoices.map((invoice) => (
                    <Button
                      key={invoice.id}
                      size="sm"
                      variant="outline"
                      onClick={() => setPaying({ tenantId, invoice })}
                    >
                      Record {formatMoney(invoice.total)}
                    </Button>
                  ))}
                </TableCell>
                <TableCell className="space-x-2 whitespace-nowrap">
                  <Button size="sm" variant="outline" disabled={pending} onClick={() => setExtending(tenantId)}>
                    Extend
                  </Button>
                  <Button size="sm" variant="outline" disabled={pending} onClick={() => setInvoicing(tenantId)}>
                    Raise invoice
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      {paying && (
        <RecordDialog
          title={`Record payment for ${paying.invoice.invoiceNumber}`}
          fields={PAYMENT}
          record={{ amount: paying.invoice.total.toFixed(2) }}
          onClose={() => setPaying(null)}
          onSubmit={async (values) => {
            await api.post(`${path}/${paying.tenantId}/invoices/${paying.invoice.id}/payments`, {
              ...values,
              failed: false,
            });
            setPaying(null);
            await refresh();
          }}
        />
      )}
      {extending && (
        <RecordDialog
          title="Extend subscription"
          fields={EXTEND}
          onClose={() => setExtending(null)}
          onSubmit={async (values) => {
            await api.post(`${path}/${extending}/extend`, values);
            setExtending(null);
            await refresh();
          }}
        />
      )}
      {invoicing && (
        <RecordDialog
          title="Raise invoice"
          fields={invoiceFields}
          onClose={() => setInvoicing(null)}
          onSubmit={async (values) => {
            await api.post(`${path}/${invoicing}/invoices`, values);
            setInvoicing(null);
            await refresh();
          }}
        />
      )}
    </div>
  );
}
