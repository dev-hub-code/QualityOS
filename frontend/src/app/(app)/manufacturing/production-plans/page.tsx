"use client";

import type { ColumnDef } from "@tanstack/react-table";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { planFields } from "@/components/manufacturing/work-order-fields";
import { DataTable } from "@/components/records/data-table";
import { PageHeader, RecordLink, StatusBadge, useFacilities } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import type { PlanDetail, ProductionPlan } from "@/lib/types";

const COLUMNS: ColumnDef<ProductionPlan>[] = [
  {
    header: "Number",
    cell: ({ row }) => (
      <RecordLink href={`/manufacturing/production-plans/${row.original.id}`}>{row.original.planNumber}</RecordLink>
    ),
  },
  { header: "Name", accessorKey: "name" },
  { header: "Facility", accessorKey: "facilityCode" },
  { header: "Period", cell: ({ row }) => `${row.original.periodStart} to ${row.original.periodEnd}` },
  { header: "Status", cell: ({ row }) => <StatusBadge status={row.original.status} /> },
];

export default function ProductionPlansPage() {
  const canManage = usePermission("PRODUCTION_PLAN_MANAGE");
  const facilities = useFacilities();
  const router = useRouter();
  const [adding, setAdding] = useState(false);
  return (
    <div className="space-y-6">
      <PageHeader title="Production plans" subtitle="What each facility means to make over a period">
        {canManage && <Button onClick={() => setAdding(true)}>New plan</Button>}
      </PageHeader>
      <DataTable
        path="/manufacturing/production-plans"
        columns={COLUMNS}
        searchLabel="Search plans"
        emptyMessage="No production plans yet."
      />
      {adding && (
        <RecordDialog
          title="New production plan"
          fields={planFields(facilities)}
          onClose={() => setAdding(false)}
          onSubmit={(values) =>
            api
              .post<PlanDetail>("/manufacturing/production-plans", values)
              .then((created) => router.push(`/manufacturing/production-plans/${created.plan.id}`))
          }
        />
      )}
    </div>
  );
}
