"use client";

import type { ColumnDef } from "@tanstack/react-table";
import { useState } from "react";
import { instrumentFields } from "@/components/calibration/instrument-fields";
import { DataTable } from "@/components/records/data-table";
import { PageHeader, RecordLink, StatusBadge, useAssignees, useFacilities, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import type { Instrument } from "@/lib/types";

const COLUMNS: ColumnDef<Instrument>[] = [
  {
    header: "Instrument ID",
    cell: ({ row }) => <RecordLink href={`/calibration/${row.original.id}`}>{row.original.instrumentCode}</RecordLink>,
  },
  { header: "Name", accessorKey: "name" },
  { header: "Category", accessorKey: "category" },
  { header: "Next due", accessorKey: "nextDueOn" },
  {
    header: "Calibration",
    cell: ({ row }) => <StatusBadge status={row.original.dueState === "OK" ? "IN_DATE" : row.original.dueState} />,
  },
  { header: "Status", cell: ({ row }) => <StatusBadge status={row.original.status} /> },
];

export default function CalibrationPage() {
  const canManage = usePermission("CALIBRATION_MANAGE");
  const facilities = useFacilities();
  const assignees = useAssignees();
  const refresh = useRefresh();
  const [adding, setAdding] = useState(false);
  return (
    <div className="space-y-6">
      <PageHeader title="Calibration">
        {canManage && <Button onClick={() => setAdding(true)}>Register instrument</Button>}
      </PageHeader>
      <DataTable path="/instruments" columns={COLUMNS} searchLabel="Search instruments" emptyMessage="No instruments." />
      {adding && (
        <RecordDialog
          title="Register instrument"
          fields={instrumentFields(facilities, assignees)}
          onClose={() => setAdding(false)}
          onSubmit={async (values) => {
            await api.post("/instruments", values);
            refresh();
          }}
        />
      )}
    </div>
  );
}
