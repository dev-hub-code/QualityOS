"use client";

import { useQuery } from "@tanstack/react-query";
import { createColumnHelper } from "@tanstack/react-table";
import { useState } from "react";
import { DataTable } from "@/components/records/data-table";
import { PageHeader, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api, type PageResponse } from "@/lib/api";
import { formatQuantity, type FieldDef } from "@/lib/record-form";
import { parseSerials } from "@/lib/traceability";
import type { Customer, Dispatch, ProductionBatch } from "@/lib/types";

const RELEASED = "/production-batches?status=RELEASED&size=100";

const column = createColumnHelper<Dispatch>();
const columns = [
  column.accessor("dispatchNumber", { header: "Number" }),
  column.accessor("customerName", { header: "Customer" }),
  column.accessor("dispatchedOn", { header: "Date" }),
  column.accessor("lines", {
    header: "Shipped",
    enableSorting: false,
    cell: (info) =>
      info
        .getValue()
        .map((line) => `${line.batchNumber} × ${formatQuantity(line.quantity)}`)
        .join(", "),
  }),
];

function DispatchDialog({ onClose }: { onClose: () => void }) {
  const refresh = useRefresh();
  const { data: customers = [] } = useQuery({
    queryKey: ["/customers"],
    queryFn: () => api.get<Customer[]>("/customers"),
  });
  const { data: batches } = useQuery({
    queryKey: [RELEASED],
    queryFn: () => api.get<PageResponse<ProductionBatch>>(RELEASED),
  });
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
    {
      name: "productionBatchId",
      label: "Released batch",
      type: "select",
      required: true,
      options: (batches?.items ?? [])
        .filter((batch) => Number(batch.availableQuantity) > 0)
        .map((batch) => ({
          value: batch.id,
          label: `${batch.batchNumber} – ${batch.productCode} (${formatQuantity(batch.availableQuantity)} available)`,
        })),
    },
    { name: "quantity", label: "Quantity", type: "decimal", required: true },
    { name: "serials", label: "Serial numbers, if tracked (one per line)", type: "textarea" },
  ];
  return (
    <RecordDialog
      title="Record dispatch"
      fields={fields}
      onClose={onClose}
      onSubmit={({ productionBatchId, quantity, serials, ...values }) =>
        api
          .post("/dispatches", {
            ...values,
            items: [{ productionBatchId, quantity, serialNumbers: parseSerials(String(serials ?? "")) }],
          })
          .then(refresh)
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
