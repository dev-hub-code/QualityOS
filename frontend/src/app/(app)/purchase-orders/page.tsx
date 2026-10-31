"use client";

import { createColumnHelper } from "@tanstack/react-table";
import Link from "next/link";
import { DataTable } from "@/components/records/data-table";
import { PageHeader, RecordLink, StatusBadge } from "@/components/records/parts";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import type { PurchaseOrderSummary } from "@/lib/types";

const column = createColumnHelper<PurchaseOrderSummary>();
const columns = [
  column.accessor("poNumber", {
    header: "Number",
    cell: (info) => <RecordLink href={`/purchase-orders/${info.row.original.id}`}>{info.getValue()}</RecordLink>,
  }),
  column.accessor("supplierName", { header: "Supplier" }),
  column.accessor("orderDate", { header: "Ordered" }),
  column.accessor("expectedDate", { header: "Expected" }),
  column.accessor("status", { header: "Status", cell: (info) => <StatusBadge status={info.getValue()} /> }),
];

export default function PurchaseOrdersPage() {
  const canManage = usePermission("PURCHASE_MANAGE");
  return (
    <div className="space-y-6">
      <PageHeader title="Purchase orders">
        {canManage && <Button render={<Link href="/purchase-orders/new" />}>New purchase order</Button>}
      </PageHeader>
      <DataTable
        path="/purchase-orders"
        columns={columns}
        searchLabel="Search by number"
        emptyMessage="No purchase orders yet."
      />
    </div>
  );
}
