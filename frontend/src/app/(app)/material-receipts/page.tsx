"use client";

import { createColumnHelper } from "@tanstack/react-table";
import { DataTable } from "@/components/records/data-table";
import { PageHeader, RecordLink, StatusBadge } from "@/components/records/parts";
import type { ReceiptSummary } from "@/lib/types";

const column = createColumnHelper<ReceiptSummary>();
const columns = [
  column.accessor("grnNumber", {
    header: "GRN",
    cell: (info) => <RecordLink href={`/material-receipts/${info.row.original.id}`}>{info.getValue()}</RecordLink>,
  }),
  column.accessor("supplierName", { header: "Supplier" }),
  column.accessor("receivedOn", { header: "Received" }),
  column.accessor("status", { header: "Status", cell: (info) => <StatusBadge status={info.getValue()} /> }),
];

export default function MaterialReceiptsPage() {
  return (
    <div className="space-y-6">
      <PageHeader title="Material receipts" subtitle="Receive material from an issued purchase order." />
      <DataTable
        path="/material-receipts"
        columns={columns}
        searchLabel="Search receipts"
        emptyMessage="No material received yet."
      />
    </div>
  );
}
