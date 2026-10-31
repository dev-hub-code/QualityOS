"use client";

import { createColumnHelper } from "@tanstack/react-table";
import { DataTable } from "@/components/records/data-table";
import { PageHeader, RecordLink, StatusBadge } from "@/components/records/parts";
import type { CorrectionSummary } from "@/lib/types";

const column = createColumnHelper<CorrectionSummary>();
const columns = [
  column.accessor("correctionNumber", {
    header: "Number",
    cell: (info) => <RecordLink href={`/material-corrections/${info.row.original.id}`}>{info.getValue()}</RecordLink>,
  }),
  column.accessor("subject", { header: "Batch" }),
  column.accessor("status", { header: "Status", cell: (info) => <StatusBadge status={info.getValue()} /> }),
  column.accessor("reason", { header: "Reason" }),
  column.accessor("requestedByName", { header: "Requested by", cell: (info) => info.getValue() ?? "—" }),
];

export default function MaterialCorrectionsPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Material corrections"
        subtitle="Start a correction from a lot's trace, or from a production batch's material used."
      />
      <DataTable
        path="/material-corrections"
        columns={columns}
        searchLabel="Search by number"
        emptyMessage="No corrections yet."
      />
    </div>
  );
}
