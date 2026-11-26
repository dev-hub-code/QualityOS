"use client";

import { useQuery } from "@tanstack/react-query";
import { createColumnHelper } from "@tanstack/react-table";
import { useState } from "react";
import { StartInspectionDialog } from "@/components/inspections/start-inspection-dialog";
import { DataTable } from "@/components/records/data-table";
import { PageHeader, RecordLink, Section, StatusBadge } from "@/components/records/parts";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api, type PageResponse } from "@/lib/api";
import { formatQuantity } from "@/lib/record-form";
import type { DispatchReturn, InspectionSummary, ReceivedBatch } from "@/lib/types";

const column = createColumnHelper<InspectionSummary>();
const columns = [
  column.accessor("inspectionNumber", {
    header: "Number",
    cell: (info) => <RecordLink href={`/inspections/${info.row.original.id}`}>{info.getValue()}</RecordLink>,
  }),
  column.accessor("materialName", { header: "Item" }),
  column.accessor("batchNumber", { header: "Batch" }),
  column.accessor("supplierName", { header: "Supplier", cell: (info) => info.getValue() ?? "—" }),
  column.accessor("status", { header: "Status", cell: (info) => <StatusBadge status={info.getValue()} /> }),
  column.accessor("disposition", {
    header: "Disposition",
    cell: (info) => {
      const value = info.getValue();
      return value ? <StatusBadge status={value} /> : "—";
    },
  }),
];

function Queue() {
  const canCreate = usePermission("INSPECTION_CREATE");
  const [starting, setStarting] = useState<ReceivedBatch | null>(null);
  const [returning, setReturning] = useState<{ id: string; label: string } | null>(null);
  const path = "/material-receipts/awaiting-inspection";
  const { data } = useQuery({
    queryKey: [path],
    queryFn: () => api.get<PageResponse<ReceivedBatch>>(`${path}?size=50`),
  });
  // Goods customers sent back wait here too, once received; only someone who can inspect is shown them.
  const returnsPath = "/dispatch-reversals/awaiting-inspection";
  const { data: returns = [] } = useQuery({
    queryKey: [returnsPath],
    queryFn: () => api.get<DispatchReturn[]>(returnsPath),
    enabled: canCreate,
  });
  const batches = data?.items ?? [];
  const returnedItems = returns.flatMap((reversal) =>
    reversal.items.map((item) => ({ reversal, item })),
  );
  return (
    <Section title="Awaiting inspection">
      {batches.length === 0 && returnedItems.length === 0 && (
        <p className="text-sm text-muted-foreground">Nothing is waiting.</p>
      )}
      <ul className="divide-y text-sm">
        {batches.map((batch) => (
          <li key={batch.itemId} className="flex flex-wrap items-center justify-between gap-3 py-3">
            <div>
              <p className="font-medium">
                {batch.materialCode} – {batch.materialName}
              </p>
              <p className="text-muted-foreground">
                {batch.internalBatch} · {formatQuantity(batch.quantity)} · {batch.supplierName} · {batch.grnNumber}
              </p>
            </div>
            {canCreate && <Button onClick={() => setStarting(batch)}>Inspect</Button>}
          </li>
        ))}
        {returnedItems.map(({ reversal, item }) => (
          <li key={item.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
            <div>
              <p className="font-medium">
                {item.productCode} returned by {reversal.customerName}
              </p>
              <p className="text-muted-foreground">
                {item.batchNumber} · {formatQuantity(item.quantity)} ·{" "}
                <RecordLink href={`/dispatch-reversals/${reversal.id}`}>{reversal.reversalNumber}</RecordLink>
              </p>
            </div>
            <Button onClick={() => setReturning({ id: item.id, label: item.batchNumber })}>Inspect</Button>
          </li>
        ))}
      </ul>
      {returning && (
        <StartInspectionDialog
          title={`Inspect returned ${returning.label}`}
          subject={{ returnItemId: returning.id }}
          onClose={() => setReturning(null)}
        />
      )}
      {starting && (
        <StartInspectionDialog
          title={`Inspect ${starting.internalBatch}`}
          subject={{ receiptItemId: starting.itemId }}
          onClose={() => setStarting(null)}
        />
      )}
    </Section>
  );
}

export default function InspectionsPage() {
  return (
    <div className="space-y-6">
      <PageHeader title="Inspections" />
      <Queue />
      <DataTable
        path="/inspections"
        columns={columns}
        searchLabel="Search by number or batch"
        emptyMessage="No inspections yet."
      />
    </div>
  );
}
