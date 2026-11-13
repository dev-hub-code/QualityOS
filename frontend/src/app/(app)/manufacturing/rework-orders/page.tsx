"use client";

import { createColumnHelper } from "@tanstack/react-table";
import { useState } from "react";
import { DataTable } from "@/components/records/data-table";
import { PageHeader, RecordLink, StatusBadge, useUnits } from "@/components/records/parts";
import { formatQuantity } from "@/lib/record-form";
import type { ReworkStatus, ReworkSummary } from "@/lib/types";

const STATUSES: ReworkStatus[] = ["OPEN", "APPROVED", "IN_PROGRESS", "AWAITING_INSPECTION", "CLOSED", "CANCELLED"];
const column = createColumnHelper<ReworkSummary>();

/** Rework orders across work orders, newest first. They are raised from a work order's quality section. */
export default function ReworkOrdersPage() {
  const units = useUnits();
  const [status, setStatus] = useState("");
  const columns = [
    column.accessor("reworkNumber", {
      header: "Rework order",
      cell: (info) => <RecordLink href={`/manufacturing/rework-orders/${info.row.original.id}`}>{info.getValue()}</RecordLink>,
    }),
    column.accessor("workOrderNumber", {
      header: "Work order",
      cell: (info) => <RecordLink href={`/manufacturing/work-orders/${info.row.original.workOrderId}`}>{info.getValue()}</RecordLink>,
    }),
    column.accessor("productCode", { header: "Product", cell: (info) => `${info.getValue() ?? ""} – ${info.row.original.productName ?? ""}` }),
    column.accessor("quantity", {
      header: "Quantity",
      cell: (info) => `${formatQuantity(info.getValue())} ${units.label(info.row.original.uomId)}`,
    }),
    column.accessor("status", { header: "Status", cell: (info) => <StatusBadge status={info.getValue()} /> }),
    column.accessor("finalDisposition", {
      header: "Outcome",
      cell: (info) => info.getValue()?.replaceAll("_", " ").toLowerCase() ?? "–",
    }),
  ];
  return (
    <div className="space-y-6">
      <PageHeader title="Rework orders" subtitle="Rework of rejected production, closed only by a reinspection's decision." />
      <select
        aria-label="Filter by status"
        className="h-9 rounded-xl border border-input bg-white/70 px-2 text-sm"
        value={status}
        onChange={(event) => setStatus(event.target.value)}
      >
        <option value="">All statuses</option>
        {STATUSES.map((value) => (
          <option key={value} value={value}>
            {value.replaceAll("_", " ").toLowerCase()}
          </option>
        ))}
      </select>
      <DataTable
        path="/manufacturing/rework-orders"
        columns={columns}
        filter={status && `status=${status}`}
        searchLabel="Search by rework or work order number"
        emptyMessage="No rework orders."
      />
    </div>
  );
}
