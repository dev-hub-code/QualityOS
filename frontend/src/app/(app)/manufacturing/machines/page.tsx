"use client";

import type { ColumnDef } from "@tanstack/react-table";
import { useState } from "react";
import { machineFields } from "@/components/manufacturing/work-center-fields";
import { DataTable } from "@/components/records/data-table";
import { PageHeader, RecordLink, StatusBadge, useRefresh, useWorkCenters } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import type { Machine } from "@/lib/types";

const COLUMNS: ColumnDef<Machine>[] = [
  {
    header: "Code",
    cell: ({ row }) => <RecordLink href={`/manufacturing/machines/${row.original.id}`}>{row.original.code}</RecordLink>,
  },
  { header: "Name", accessorKey: "name" },
  { header: "Work centre", accessorKey: "workCenterCode" },
  { header: "Status", cell: ({ row }) => <StatusBadge status={row.original.status} /> },
];

export default function MachinesPage() {
  const canManage = usePermission("MANUFACTURING_MANAGE");
  const workCenters = useWorkCenters();
  const refresh = useRefresh();
  const [adding, setAdding] = useState(false);
  return (
    <div className="space-y-6">
      <PageHeader title="Machines">
        {canManage && <Button onClick={() => setAdding(true)}>Add machine</Button>}
      </PageHeader>
      <DataTable path="/manufacturing/machines" columns={COLUMNS} searchLabel="Search machines" emptyMessage="No machines yet." />
      {adding && (
        <RecordDialog
          title="Add machine"
          fields={machineFields(workCenters)}
          onClose={() => setAdding(false)}
          onSubmit={(values) => api.post("/manufacturing/machines", values).then(refresh)}
        />
      )}
    </div>
  );
}
