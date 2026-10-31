"use client";

import { createColumnHelper } from "@tanstack/react-table";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { DataTable } from "@/components/records/data-table";
import { PageHeader, RecordLink, StatusBadge, useFacilities, useProducts, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { formatQuantity, type FieldDef } from "@/lib/record-form";
import type { BatchDetail, ProductionBatch } from "@/lib/types";

const column = createColumnHelper<ProductionBatch>();
const columns = [
  column.accessor("batchNumber", {
    header: "Batch",
    cell: (info) => <RecordLink href={`/production-batches/${info.row.original.id}`}>{info.getValue()}</RecordLink>,
  }),
  column.accessor("productName", { header: "Product" }),
  column.accessor("status", { header: "Status", cell: (info) => <StatusBadge status={info.getValue()} /> }),
  column.accessor("plannedQuantity", { header: "Planned", cell: (info) => formatQuantity(info.getValue()) }),
  column.accessor("availableQuantity", { header: "Available", cell: (info) => formatQuantity(info.getValue()) }),
];

export default function ProductionBatchesPage() {
  const canManage = usePermission("TRACEABILITY_MANAGE");
  const router = useRouter();
  const refresh = useRefresh();
  const products = useProducts();
  const facilities = useFacilities();
  const [adding, setAdding] = useState(false);
  const fields: FieldDef[] = [
    { name: "productId", label: "Product", type: "select", required: true, options: products },
    { name: "facilityId", label: "Facility", type: "select", required: true, options: facilities },
    { name: "plannedQuantity", label: "Planned quantity", type: "decimal", required: true },
  ];
  return (
    <div className="space-y-6">
      <PageHeader title="Production batches">
        {canManage && <Button onClick={() => setAdding(true)}>Start batch</Button>}
      </PageHeader>
      <DataTable path="/production-batches" columns={columns} searchLabel="Search batches" emptyMessage="No batches yet." />
      {adding && (
        <RecordDialog
          title="Start batch"
          fields={fields}
          onClose={() => setAdding(false)}
          onSubmit={async (values) => {
            const created = await api.post<BatchDetail>("/production-batches", values);
            await refresh();
            router.push(`/production-batches/${created.batch.id}`);
          }}
        />
      )}
    </div>
  );
}
