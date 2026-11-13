"use client";

import type { ColumnDef } from "@tanstack/react-table";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { workOrderFields } from "@/components/manufacturing/work-order-fields";
import { DataTable } from "@/components/records/data-table";
import { PageHeader, RecordLink, StatusBadge, useFacilities, useProducts } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { PRIORITY_LABELS } from "@/lib/manufacturing";
import { formatQuantity } from "@/lib/record-form";
import type { WorkOrder, WorkOrderDetail } from "@/lib/types";

const COLUMNS: ColumnDef<WorkOrder>[] = [
  {
    header: "Number",
    cell: ({ row }) => (
      <RecordLink href={`/manufacturing/work-orders/${row.original.id}`}>{row.original.workOrderNumber}</RecordLink>
    ),
  },
  { header: "Product", cell: ({ row }) => `${row.original.productCode} – ${row.original.productName}` },
  { header: "Quantity", cell: ({ row }) => formatQuantity(row.original.quantity) },
  { header: "Facility", accessorKey: "facilityCode" },
  { header: "Planned", cell: ({ row }) => `${row.original.plannedStart} to ${row.original.plannedEnd}` },
  { header: "Priority", cell: ({ row }) => PRIORITY_LABELS[row.original.priority] },
  { header: "Status", cell: ({ row }) => <StatusBadge status={row.original.status} /> },
];

export default function WorkOrdersPage() {
  const canManage = usePermission("WORK_ORDER_MANAGE");
  const products = useProducts();
  const facilities = useFacilities();
  const router = useRouter();
  const [adding, setAdding] = useState(false);
  return (
    <div className="space-y-6">
      <PageHeader title="Work orders" subtitle="Orders to make a quantity of a product, from draft to production">
        {canManage && <Button onClick={() => setAdding(true)}>New work order</Button>}
      </PageHeader>
      <DataTable
        path="/manufacturing/work-orders"
        columns={COLUMNS}
        searchLabel="Search by number or product"
        emptyMessage="No work orders yet."
      />
      {adding && (
        <RecordDialog
          title="New work order"
          fields={workOrderFields(products, facilities)}
          record={{ priority: "NORMAL" }}
          onClose={() => setAdding(false)}
          onSubmit={(values) =>
            api
              .post<WorkOrderDetail>("/manufacturing/work-orders", values)
              .then((created) => router.push(`/manufacturing/work-orders/${created.workOrder.id}`))
          }
        />
      )}
    </div>
  );
}
