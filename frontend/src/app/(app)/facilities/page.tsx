"use client";

import { useQueryClient } from "@tanstack/react-query";
import { createColumnHelper } from "@tanstack/react-table";
import { useState } from "react";
import { DataTable } from "@/components/records/data-table";
import { PageHeader, RecordLink } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { FACILITY_FIELDS } from "@/lib/record-fields";
import type { Facility } from "@/lib/types";

const column = createColumnHelper<Facility>();
const columns = [
  column.accessor("code", {
    header: "Code",
    cell: (info) => <RecordLink href={`/facilities/${info.row.original.id}`}>{info.getValue()}</RecordLink>,
  }),
  column.accessor("name", { header: "Name" }),
  column.accessor("city", { header: "City" }),
  column.accessor("state", { header: "State" }),
];

export default function FacilitiesPage() {
  const canManage = usePermission("FACILITY_MANAGE");
  const queryClient = useQueryClient();
  const [adding, setAdding] = useState(false);
  return (
    <div className="space-y-6">
      <PageHeader title="Facilities">
        {canManage && <Button onClick={() => setAdding(true)}>Add facility</Button>}
      </PageHeader>
      <DataTable
        path="/facilities"
        columns={columns}
        searchLabel="Search facilities"
        emptyMessage="No facilities yet."
      />
      {adding && (
        <RecordDialog
          title="Add facility"
          fields={FACILITY_FIELDS}
          onClose={() => setAdding(false)}
          onSubmit={async (values) => {
            await api.post("/facilities", values);
            await queryClient.invalidateQueries({ queryKey: ["/facilities"] });
          }}
        />
      )}
    </div>
  );
}
