"use client";

import { useQuery } from "@tanstack/react-query";
import { createColumnHelper } from "@tanstack/react-table";
import { useState } from "react";
import { DataTable } from "@/components/records/data-table";
import { PageHeader, RecordLink, useRefresh } from "@/components/records/parts";
import { DispatchActions } from "@/components/traceability/dispatch-actions";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api, ApiError } from "@/lib/api";
import { formatQuantity, type FieldDef } from "@/lib/record-form";
import { toDispatchItems } from "@/lib/traceability";
import type { Customer, Dispatch, ProductionBatch } from "@/lib/types";
import { lookupAll } from "@/components/records/parts";

const RELEASED = "/production-batches?status=RELEASED";

const column = createColumnHelper<Dispatch>();
const columns = [
  column.accessor("dispatchNumber", {
    header: "Number",
    cell: (info) => <RecordLink href={`/dispatches/${info.row.original.id}`}>{info.getValue()}</RecordLink>,
  }),
  column.accessor("customerName", {
    header: "Customer",
    cell: (info) => <RecordLink href={`/customers/${info.row.original.customerId}`}>{info.getValue()}</RecordLink>,
  }),
  column.accessor("dispatchedOn", { header: "Date" }),
  column.accessor("lines", {
    header: "Shipped",
    enableSorting: false,
    cell: (info) =>
      info
        .getValue()
        .map((line) => {
          const returned = Number(line.returnedQuantity) > 0 ? ` (${formatQuantity(line.returnedQuantity)} returned)` : "";
          return `${line.batchNumber} × ${formatQuantity(line.quantity)}${returned}`;
        })
        .join(", "),
  }),
  column.display({ id: "correction", header: "", cell: (info) => <DispatchActions dispatch={info.row.original} /> }),
];

function DispatchDialog({ onClose }: { onClose: () => void }) {
  const refresh = useRefresh();
  const { data: customers = [] } = useQuery({
    queryKey: ["/customers"],
    queryFn: () => api.get<Customer[]>("/customers"),
  });
  const { data: batches } = useQuery({
    queryKey: [RELEASED],
    queryFn: () => lookupAll<ProductionBatch>("/production-batches", "&status=RELEASED"),
  });
  const [lines, setLines] = useState(1);
  const canOverrideRecall = usePermission("RECALL_MANAGE");
  // Set once the server refuses goods under an open recall, so that someone allowed to may give a reason.
  const [heldByRecall, setHeldByRecall] = useState(false);
  const options = (batches ?? [])
    .filter((batch) => Number(batch.availableQuantity) > 0)
    .map((batch) => ({
      value: batch.id,
      label: `${batch.batchNumber} – ${batch.productCode} (${formatQuantity(batch.availableQuantity)} available)`,
    }));
  const fields: FieldDef[] = [
    {
      name: "customerId",
      label: "Customer",
      type: "select",
      required: true,
      options: customers.map((customer) => ({ value: customer.id, label: `${customer.code} – ${customer.name}` })),
    },
    { name: "dispatchedOn", label: "Date", type: "date", required: true },
    { name: "reference", label: "Invoice or vehicle reference" },
    ...Array.from({ length: lines }, (_, index): FieldDef[] => {
      const line = index + 1;
      return [
        { name: `productionBatchId${line}`, label: `Released batch ${line}`, type: "select", required: true, options },
        { name: `quantity${line}`, label: `Quantity ${line}`, type: "decimal", required: true },
        { name: `serials${line}`, label: `Serial numbers ${line}, if tracked (one per line)`, type: "textarea" },
      ];
    }).flat(),
    ...(heldByRecall && canOverrideRecall
      ? [{ name: "recallOverrideReason", label: "Reason to dispatch despite the recall", type: "textarea", required: true } as FieldDef]
      : []),
  ];
  return (
    <RecordDialog
      title="Record dispatch"
      fields={fields}
      onClose={onClose}
      extra={
        <Button type="button" variant="outline" onClick={() => setLines(lines + 1)}>
          Add another batch
        </Button>
      }
      onSubmit={(values) =>
        api
          .post("/dispatches", {
            customerId: values.customerId,
            dispatchedOn: values.dispatchedOn,
            reference: values.reference,
            items: toDispatchItems(values, lines),
            recallOverrideReason: values.recallOverrideReason || null,
          })
          .then(refresh)
          .catch((error: unknown) => {
            if (error instanceof ApiError && error.code === "RECALL_HOLD") setHeldByRecall(true);
            throw error;
          })
      }
    />
  );
}

export default function DispatchesPage() {
  const canManage = usePermission("TRACEABILITY_MANAGE");
  const [adding, setAdding] = useState(false);
  return (
    <div className="space-y-6">
      <PageHeader title="Dispatches">
        {canManage && <Button onClick={() => setAdding(true)}>Record dispatch</Button>}
      </PageHeader>
      <DataTable path="/dispatches" columns={columns} searchLabel="Search dispatches" emptyMessage="Nothing shipped yet." />
      {adding && <DispatchDialog onClose={() => setAdding(false)} />}
    </div>
  );
}
