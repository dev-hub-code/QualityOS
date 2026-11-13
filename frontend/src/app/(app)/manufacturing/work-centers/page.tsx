"use client";

import type { ColumnDef } from "@tanstack/react-table";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { workCenterFields } from "@/components/manufacturing/work-center-fields";
import { DataTable } from "@/components/records/data-table";
import { PageHeader, RecordLink, useFacilities } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import type { WorkCenter } from "@/lib/types";

const COLUMNS: ColumnDef<WorkCenter>[] = [
  {
    header: "Code",
    cell: ({ row }) => (
      <RecordLink href={`/manufacturing/work-centers/${row.original.id}`}>{row.original.code}</RecordLink>
    ),
  },
  { header: "Name", accessorKey: "name" },
  { header: "Facility", accessorKey: "facilityCode" },
  { header: "Production line", cell: ({ row }) => row.original.productionLineCode ?? "–" },
];

export default function WorkCentersPage() {
  const canManage = usePermission("MANUFACTURING_MANAGE");
  const facilities = useFacilities();
  const router = useRouter();
  const [adding, setAdding] = useState(false);
  return (
    <div className="space-y-6">
      <PageHeader title="Work centres" subtitle="Where operations are carried out, within a facility.">
        {canManage && <Button onClick={() => setAdding(true)}>Add work centre</Button>}
      </PageHeader>
      <DataTable
        path="/manufacturing/work-centers"
        columns={COLUMNS}
        searchLabel="Search work centres"
        emptyMessage="No work centres yet."
      />
      {adding && (
        <RecordDialog
          title="Add work centre"
          // A production line is chosen afterwards, from the lines of the facility chosen here.
          fields={workCenterFields(facilities, []).filter((field) => field.name !== "productionLineId")}
          onClose={() => setAdding(false)}
          onSubmit={async (values) => {
            const created = await api.post<WorkCenter>("/manufacturing/work-centers", values);
            router.push(`/manufacturing/work-centers/${created.id}`);
          }}
        />
      )}
    </div>
  );
}
