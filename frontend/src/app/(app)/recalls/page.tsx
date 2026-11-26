"use client";

import type { ColumnDef } from "@tanstack/react-table";
import { DataTable } from "@/components/records/data-table";
import { PageHeader, RecordLink, StatusBadge } from "@/components/records/parts";
import type { RecallSummary } from "@/lib/types";
import { formatCalendarDay } from "@/lib/dates";

const COLUMNS: ColumnDef<RecallSummary>[] = [
  {
    header: "Recall",
    cell: ({ row }) => <RecordLink href={`/recalls/${row.original.id}`}>{row.original.recallNumber}</RecordLink>,
  },
  { header: "Date", accessorKey: "recallDate", cell: ({ getValue }) => formatCalendarDay(getValue<string>()) },
  { header: "Batches", cell: ({ row }) => row.original.batchNumbers.join(", ") },
  { header: "Status", cell: ({ row }) => <StatusBadge status={row.original.status} /> },
];

export default function RecallsPage() {
  return (
    <div className="space-y-6">
      <PageHeader title="Recalls" subtitle="Start a recall from a batch's recall report." />
      <DataTable path="/recalls" columns={COLUMNS} searchLabel="Search recalls" emptyMessage="No recalls." />
    </div>
  );
}
