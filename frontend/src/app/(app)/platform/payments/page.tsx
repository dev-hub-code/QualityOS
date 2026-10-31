"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useState } from "react";
import { formatDate, formatMoney } from "@/components/billing/subscription-banner";
import { PageHeader, QueryState } from "@/components/records/parts";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { api } from "@/lib/api";
import type { PaymentList } from "@/lib/types";

const PAGE_SIZE = 25;
const NO_FILTER = { q: "", status: "", from: "", to: "" };

export default function PlatformPaymentsPage() {
  const [filter, setFilter] = useState(NO_FILTER);
  const [page, setPage] = useState(0);
  const chosen = new URLSearchParams(Object.entries(filter).filter(([, value]) => value)).toString();
  const path = `/platform/payments?${chosen}${chosen ? "&" : ""}page=${page}&size=${PAGE_SIZE}`;
  const { data, error } = useQuery({
    queryKey: [path],
    queryFn: () => api.get<PaymentList>(path),
    placeholderData: keepPreviousData,
  });
  const narrow = (change: Partial<typeof NO_FILTER>) => {
    setFilter({ ...filter, ...change });
    setPage(0);
  };
  return (
    <div className="space-y-6">
      <PageHeader
        title="Payments"
        subtitle="Payments are recorded by hand from Invoices, and must match the invoice total. Failed ones need following up."
      />
      <div className="flex flex-wrap items-end gap-3">
        <div className="space-y-1.5">
          <Label htmlFor="payment-search">UTR, invoice or organisation</Label>
          <Input id="payment-search" value={filter.q} onChange={(event) => narrow({ q: event.target.value })} />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="payment-status">Status</Label>
          <select
            id="payment-status"
            className="h-9 rounded-md border bg-background px-2 text-sm"
            value={filter.status}
            onChange={(event) => narrow({ status: event.target.value })}
          >
            <option value="">Any</option>
            <option value="SUCCEEDED">Succeeded</option>
            <option value="FAILED">Failed</option>
          </select>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="payment-from">Paid from</Label>
          <Input
            id="payment-from"
            type="date"
            value={filter.from}
            onChange={(event) => narrow({ from: event.target.value })}
          />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="payment-to">Paid to</Label>
          <Input id="payment-to" type="date" value={filter.to} onChange={(event) => narrow({ to: event.target.value })} />
        </div>
      </div>
      {!data ? (
        <QueryState error={error} />
      ) : data.items.length === 0 ? (
        <p className="text-sm text-muted-foreground">No payments match.</p>
      ) : (
        <>
          <p role="status" className="text-sm">
            {data.total} {data.total === 1 ? "payment" : "payments"}, page {page + 1}
          </p>
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Paid on</TableHead>
                  <TableHead>Organisation</TableHead>
                  <TableHead>Invoice</TableHead>
                  <TableHead className="text-right">Amount</TableHead>
                  <TableHead>Method</TableHead>
                  <TableHead>Reference</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Recorded by</TableHead>
                  <TableHead>Recorded</TableHead>
                  <TableHead>Notes</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {data.items.map((row) => (
                  <TableRow key={row.id}>
                    <TableCell>{formatDate(row.paidOn ?? row.recordedAt)}</TableCell>
                    <TableCell>
                      <Link className="underline" href={`/platform/organisations/${row.tenantId}`}>
                        {row.organisation}
                      </Link>
                    </TableCell>
                    <TableCell>{row.invoiceNumber}</TableCell>
                    <TableCell className="text-right">{formatMoney(row.amount)}</TableCell>
                    <TableCell>{row.method?.replace("_", " ").toLowerCase() ?? "Not recorded"}</TableCell>
                    <TableCell>{row.reference}</TableCell>
                    <TableCell>
                      {row.status.toLowerCase()}
                      {row.failureReason && `: ${row.failureReason}`}
                    </TableCell>
                    <TableCell>{row.recordedBy ?? "Not recorded"}</TableCell>
                    <TableCell>{formatDate(row.recordedAt)}</TableCell>
                    <TableCell>{row.notes}</TableCell>
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
        </>
      )}
    </div>
  );
}
