"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useState } from "react";
import { formatDate, formatMoney } from "@/components/billing/subscription-banner";
import { PageHeader, QueryState, useAction, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { EXTEND_FIELDS, RecordPaymentDialog, REASON_FIELD } from "@/components/platform/billing-dialogs";
import { Button, buttonVariants } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { api } from "@/lib/api";
import type { FieldDef } from "@/lib/record-form";
import type { Invoice, OrganisationList, Plan } from "@/lib/types";


const PAGE_SIZE = 25;
const NO_FILTER = { q: "", plan: "", state: "", unpaid: "", registeredFrom: "", registeredTo: "", sort: "" };
const SELECT_CLASS = "h-9 rounded-md border bg-background px-2 text-sm";

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
  const [filter, setFilter] = useState(NO_FILTER);
  const [page, setPage] = useState(0);
  const chosen = new URLSearchParams(Object.entries(filter).filter(([, value]) => value)).toString();
  const query = `${chosen}${chosen ? "&" : ""}page=${page}&size=${PAGE_SIZE}`;
  const { data, error } = useQuery({
    queryKey: [path, query],
    queryFn: () => api.get<OrganisationList>(`${path}?${query}`),
    placeholderData: keepPreviousData,
  });
  const narrow = (change: Partial<typeof NO_FILTER>) => {
    setFilter({ ...filter, ...change });
    setPage(0);
  };
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
    { name: "amount", label: "Amount before GST (blank for list price)", type: "decimal" },
    REASON_FIELD,
  ];
  return (
    <div className="space-y-6">
      <PageHeader title="Organisations" subtitle="Everything done here shows in that organisation's audit log.">
        <a className={buttonVariants({ variant: "outline" })} href={`/api/v1${path}/export?${chosen}`} download>
          Export CSV
        </a>
      </PageHeader>
      <div className="flex flex-wrap items-end gap-3">
        <div className="space-y-1.5">
          <Label htmlFor="organisation-search">Search name, email or GSTIN</Label>
          <Input id="organisation-search" value={filter.q} onChange={(event) => narrow({ q: event.target.value })} />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="organisation-plan">Plan</Label>
          <select
            id="organisation-plan"
            className={SELECT_CLASS}
            value={filter.plan}
            onChange={(event) => narrow({ plan: event.target.value })}
          >
            <option value="">All plans</option>
            {plans.data?.map((plan) => (
              <option key={plan.id} value={plan.code}>
                {plan.name}
              </option>
            ))}
          </select>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="organisation-state">State</Label>
          <select
            id="organisation-state"
            className={SELECT_CLASS}
            value={filter.state}
            onChange={(event) => narrow({ state: event.target.value })}
          >
            <option value="">All states</option>
            <option value="TRIAL">Trial</option>
            <option value="ACTIVE">Active</option>
            <option value="GRACE">Grace</option>
            <option value="READ_ONLY">Read-only</option>
          </select>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="organisation-payment">Payment</Label>
          <select
            id="organisation-payment"
            className={SELECT_CLASS}
            value={filter.unpaid}
            onChange={(event) => narrow({ unpaid: event.target.value })}
          >
            <option value="">Any</option>
            <option value="true">Unpaid invoice</option>
          </select>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="organisation-from">Registered from</Label>
          <Input
            id="organisation-from"
            type="date"
            value={filter.registeredFrom}
            onChange={(event) => narrow({ registeredFrom: event.target.value })}
          />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="organisation-to">Registered to</Label>
          <Input
            id="organisation-to"
            type="date"
            value={filter.registeredTo}
            onChange={(event) => narrow({ registeredTo: event.target.value })}
          />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="organisation-sort">Sort</Label>
          <select
            id="organisation-sort"
            className={SELECT_CLASS}
            value={filter.sort}
            onChange={(event) => narrow({ sort: event.target.value })}
          >
            <option value="">Unpaid first</option>
            <option value="NAME">Name</option>
            <option value="NEWEST">Newest</option>
            <option value="ENDING">Ending soonest</option>
          </select>
        </div>
      </div>
      <p role="status" className="text-sm">
        {data.total} {data.total === 1 ? "organisation" : "organisations"}, page {page + 1}
      </p>
      {message && <p className="text-sm text-destructive">{message}</p>}
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
            {data.items.map(({ tenantId, organizationName, subscription, openInvoices, suspendedAt }) => (
              <TableRow key={tenantId}>
                <TableCell>
                  <Link className="underline" href={`/platform/organisations/${tenantId}`}>
                    {organizationName}
                  </Link>
                </TableCell>
                <TableCell>{subscription.planName}</TableCell>
                <TableCell>{suspendedAt ? "suspended" : subscription.state.replace("_", " ").toLowerCase()}</TableCell>
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
      <div className="flex gap-2">
        <Button variant="outline" disabled={page === 0} onClick={() => setPage(page - 1)}>
          Previous
        </Button>
        <Button variant="outline" disabled={(page + 1) * PAGE_SIZE >= data.total} onClick={() => setPage(page + 1)}>
          Next
        </Button>
      </div>
      {paying && (
        <RecordPaymentDialog
          tenantId={paying.tenantId}
          invoiceId={paying.invoice.id}
          invoiceNumber={paying.invoice.invoiceNumber}
          amount={paying.invoice.total}
          onClose={() => setPaying(null)}
        />
      )}
      {extending && (
        <RecordDialog
          title="Extend subscription"
          fields={EXTEND_FIELDS}
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
