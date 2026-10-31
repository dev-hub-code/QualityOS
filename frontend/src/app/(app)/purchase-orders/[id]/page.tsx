"use client";

import { useQuery } from "@tanstack/react-query";
import { createColumnHelper } from "@tanstack/react-table";
import Link from "next/link";
import { useParams } from "next/navigation";
import { FormError } from "@/components/forms/field";
import { DataTable } from "@/components/records/data-table";
import {
  Details,
  PageHeader,
  QueryState,
  RecordLink,
  Section,
  StatusBadge,
  useAction,
  useUnits,
} from "@/components/records/parts";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { formatQuantity } from "@/lib/record-form";
import type { PurchaseOrder, ReceiptSummary } from "@/lib/types";

const column = createColumnHelper<ReceiptSummary>();
const receiptColumns = [
  column.accessor("grnNumber", {
    header: "GRN",
    cell: (info) => <RecordLink href={`/material-receipts/${info.row.original.id}`}>{info.getValue()}</RecordLink>,
  }),
  column.accessor("receivedOn", { header: "Received" }),
  column.accessor("status", { header: "Status", cell: (info) => <StatusBadge status={info.getValue()} /> }),
];

export default function PurchaseOrderPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/purchase-orders/${id}`;
  const canManage = usePermission("PURCHASE_MANAGE");
  const canReceive = usePermission("RECEIPT_MANAGE");
  const units = useUnits();
  const { run, pending, message } = useAction();
  const { data: order, error } = useQuery({ queryKey: [path], queryFn: () => api.get<PurchaseOrder>(path) });
  if (!order) return <QueryState error={error} />;

  const act = (action: string) => run(() => api.post(`${path}/${action}`));
  const { status } = order;
  const open = status === "ISSUED" || status === "PARTIALLY_RECEIVED";

  return (
    <div className="space-y-6">
      <PageHeader title={order.poNumber} subtitle={<StatusBadge status={status} />}>
        {canManage && status === "DRAFT" && (
          <>
            <Button disabled={pending} onClick={() => act("issue")}>
              Issue
            </Button>
            <Button variant="outline" render={<Link href={`/purchase-orders/${id}/edit`} />}>
              Edit
            </Button>
          </>
        )}
        {canReceive && open && <Button render={<Link href={`/purchase-orders/${id}/receive`} />}>Receive material</Button>}
        {canManage && (open || status === "RECEIVED") && (
          <Button variant="outline" disabled={pending} onClick={() => act("close")}>
            Close
          </Button>
        )}
        {canManage && (status === "DRAFT" || status === "ISSUED") && (
          <Button variant="destructive" disabled={pending} onClick={() => act("cancel")}>
            Cancel order
          </Button>
        )}
      </PageHeader>
      <FormError message={message} />
      <Section title="Details">
        <Details
          items={[
            ["Supplier", order.supplierName],
            ["Order date", order.orderDate],
            ["Expected date", order.expectedDate],
            ["Notes", order.notes],
          ]}
        />
      </Section>
      <Section title="Items">
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Line</TableHead>
                <TableHead>Material</TableHead>
                <TableHead>Ordered</TableHead>
                <TableHead>Received</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {order.items.map((item) => (
                <TableRow key={item.id}>
                  <TableCell>{item.lineNumber}</TableCell>
                  <TableCell>
                    {item.materialCode} – {item.materialName}
                  </TableCell>
                  <TableCell>
                    {formatQuantity(item.orderedQuantity)} {units.label(item.uomId)}
                  </TableCell>
                  <TableCell>{formatQuantity(item.receivedQuantity)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </Section>
      <Section title="Receipts">
        <DataTable
          path="/material-receipts"
          columns={receiptColumns}
          filter={`purchaseOrderId=${id}`}
          emptyMessage="Nothing received yet."
        />
      </Section>
    </div>
  );
}
