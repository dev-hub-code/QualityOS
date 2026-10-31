"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useState } from "react";
import { formatDate, formatMoney } from "@/components/billing/subscription-banner";
import { RecordPaymentDialog } from "@/components/platform/billing-dialogs";
import { Details, PageHeader, QueryState } from "@/components/records/parts";
import { Button, buttonVariants } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import type { Invoice, InvoiceList, PlatformInvoice } from "@/lib/types";

const PAGE_SIZE = 25;
const NO_FILTER = { q: "", status: "", overdue: "", from: "", to: "", min: "", max: "" };
const SELECT_CLASS = "h-9 rounded-md border bg-background px-2 text-sm";

const period = ({ periodStartsAt, periodEndsAt }: PlatformInvoice) =>
  periodStartsAt && periodEndsAt ? `${formatDate(periodStartsAt)} to ${formatDate(periodEndsAt)}` : "Starts on payment";

function taxLines(invoice: Invoice): [string, string][] {
  if (invoice.cgstAmount !== null && invoice.sgstAmount !== null) {
    return [
      ["CGST", formatMoney(invoice.cgstAmount)],
      ["SGST", formatMoney(invoice.sgstAmount)],
    ];
  }
  if (invoice.igstAmount !== null) return [["IGST", formatMoney(invoice.igstAmount)]];
  return [[`GST at ${invoice.taxPercent}%`, formatMoney(invoice.taxAmount)]];
}

function InvoiceDetails({ row, onClose }: { row: PlatformInvoice; onClose: () => void }) {
  const path = `/platform/organisations/${row.tenantId}/invoices/${row.id}`;
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<Invoice>(path) });
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{row.invoiceNumber}</DialogTitle>
        </DialogHeader>
        {data ? (
          <Details
            items={[
              ["Billing period", period(row)],
              ["Issued", formatDate(row.issuedOn)],
              ["Billed to", data.buyerName],
              ["Buyer GSTIN", data.buyerGstin ?? "Not recorded"],
              ["Seller GSTIN", data.sellerGstin ?? "Not recorded"],
              ["Place of supply", data.placeOfSupply ?? "Not determined"],
              ["SAC", data.sacCode ?? "Not set"],
              ["Taxable value", formatMoney(data.amount)],
              ...taxLines(data),
              ["Total", formatMoney(data.total)],
              ["Paid", formatMoney(row.paid)],
              ["Outstanding", formatMoney(row.outstanding)],
            ]}
          />
        ) : (
          <QueryState error={error} />
        )}
      </DialogContent>
    </Dialog>
  );
}

export default function PlatformInvoicesPage() {
  const billing = usePermission("PLATFORM_BILLING_MANAGE");
  const [filter, setFilter] = useState(NO_FILTER);
  const [page, setPage] = useState(0);
  const [viewing, setViewing] = useState<PlatformInvoice | null>(null);
  const [paying, setPaying] = useState<PlatformInvoice | null>(null);
  const chosen = new URLSearchParams(Object.entries(filter).filter(([, value]) => value)).toString();
  const path = `/platform/invoices?${chosen}${chosen ? "&" : ""}page=${page}&size=${PAGE_SIZE}`;
  const { data, error } = useQuery({
    queryKey: [path],
    queryFn: () => api.get<InvoiceList>(path),
    placeholderData: keepPreviousData,
  });
  const narrow = (change: Partial<typeof NO_FILTER>) => {
    setFilter({ ...filter, ...change });
    setPage(0);
  };
  const box = (name: "q" | "from" | "to" | "min" | "max", label: string, type = "text") => (
    <div className="space-y-1.5">
      <Label htmlFor={`invoice-${name}`}>{label}</Label>
      <Input
        id={`invoice-${name}`}
        type={type}
        value={filter[name]}
        onChange={(event) => narrow({ [name]: event.target.value })}
      />
    </div>
  );
  return (
    <div className="space-y-6">
      <PageHeader title="Invoices" subtitle="Amounts are as issued, and an issued invoice never changes.">
        <a className={buttonVariants({ variant: "outline" })} href={`/api/v1/platform/invoices/export?${chosen}`}>
          Export CSV
        </a>
      </PageHeader>
      <div className="flex flex-wrap items-end gap-3">
        {box("q", "Invoice or organisation")}
        <div className="space-y-1.5">
          <Label htmlFor="invoice-status">Status</Label>
          <select
            id="invoice-status"
            className={SELECT_CLASS}
            value={filter.status}
            onChange={(event) => narrow({ status: event.target.value })}
          >
            <option value="">Any</option>
            <option value="ISSUED">Issued</option>
            <option value="PAID">Paid</option>
            <option value="VOID">Void</option>
          </select>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="invoice-overdue">Due</Label>
          <select
            id="invoice-overdue"
            className={SELECT_CLASS}
            value={filter.overdue}
            onChange={(event) => narrow({ overdue: event.target.value })}
          >
            <option value="">Any</option>
            <option value="true">Overdue</option>
          </select>
        </div>
        {box("from", "Issued from", "date")}
        {box("to", "Issued to", "date")}
        {box("min", "Lowest total", "number")}
        {box("max", "Highest total", "number")}
      </div>
      {!data ? (
        <QueryState error={error} />
      ) : data.items.length === 0 ? (
        <p className="text-sm text-muted-foreground">No invoices match.</p>
      ) : (
        <>
          <p role="status" className="text-sm">
            {data.total} {data.total === 1 ? "invoice" : "invoices"}, page {page + 1}
          </p>
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Invoice</TableHead>
                  <TableHead>Organisation</TableHead>
                  <TableHead className="text-right">Total</TableHead>
                  <TableHead className="text-right">Paid</TableHead>
                  <TableHead className="text-right">Outstanding</TableHead>
                  <TableHead>Due</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead />
                </TableRow>
              </TableHeader>
              <TableBody>
                {data.items.map((row) => (
                  <TableRow key={row.id}>
                    <TableCell>{row.invoiceNumber}</TableCell>
                    <TableCell>
                      <Link className="underline" href={`/platform/organisations/${row.tenantId}`}>
                        {row.organisation}
                      </Link>
                    </TableCell>
                    <TableCell className="text-right">{formatMoney(row.total)}</TableCell>
                    <TableCell className="text-right">{formatMoney(row.paid)}</TableCell>
                    <TableCell className="text-right">{formatMoney(row.outstanding)}</TableCell>
                    <TableCell>{formatDate(row.dueOn)}</TableCell>
                    <TableCell>{row.status.toLowerCase()}</TableCell>
                    <TableCell className="space-x-2 whitespace-nowrap">
                      <Button size="sm" variant="outline" onClick={() => setViewing(row)}>
                        View
                      </Button>
                      {billing && row.status === "ISSUED" && (
                        <Button size="sm" variant="outline" onClick={() => setPaying(row)}>
                          Record payment
                        </Button>
                      )}
                      <a
                        className={buttonVariants({ size: "sm", variant: "outline" })}
                        href={`/api/v1/platform/organisations/${row.tenantId}/invoices/${row.id}/pdf`}
                      >
                        PDF
                      </a>
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
        </>
      )}
      {viewing && <InvoiceDetails row={viewing} onClose={() => setViewing(null)} />}
      {paying && (
        <RecordPaymentDialog
          tenantId={paying.tenantId}
          invoiceId={paying.id}
          invoiceNumber={paying.invoiceNumber}
          amount={paying.outstanding}
          onClose={() => setPaying(null)}
        />
      )}
    </div>
  );
}
