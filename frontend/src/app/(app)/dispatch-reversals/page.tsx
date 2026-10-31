"use client";

import { createColumnHelper } from "@tanstack/react-table";
import { DataTable } from "@/components/records/data-table";
import { PageHeader, RecordLink, StatusBadge } from "@/components/records/parts";
import type { DispatchReturn } from "@/lib/types";

const column = createColumnHelper<DispatchReturn>();
const columns = [
  column.accessor("reversalNumber", {
    header: "Number",
    cell: (info) => <RecordLink href={`/dispatch-reversals/${info.row.original.id}`}>{info.getValue()}</RecordLink>,
  }),
  column.accessor("returnReference", { header: "Reference" }),
  column.accessor("dispatchNumber", { header: "Dispatch" }),
  column.accessor("customerName", { header: "Customer" }),
  column.accessor("reversalDate", { header: "Date" }),
  column.accessor("status", { header: "Status", cell: (info) => <StatusBadge status={info.getValue()} /> }),
];

export default function DispatchReversalsPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Returns"
        subtitle="Record a return from the dispatch. Goods stay out of stock until an inspection accepts them."
      />
      <DataTable path="/dispatch-reversals" columns={columns} searchLabel="" emptyMessage="No returns yet." />
    </div>
  );
}
