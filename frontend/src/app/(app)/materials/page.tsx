"use client";

import { createColumnHelper } from "@tanstack/react-table";
import { useState } from "react";
import { DataTable } from "@/components/records/data-table";
import { PageHeader, RecordLink, useProductCategories, useRefresh, useUnits } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { materialFields } from "@/lib/record-fields";
import type { Material } from "@/lib/types";

const column = createColumnHelper<Material>();
const columns = [
  column.accessor("code", {
    header: "Code",
    cell: (info) => <RecordLink href={`/materials/${info.row.original.id}`}>{info.getValue()}</RecordLink>,
  }),
  column.accessor("name", { header: "Name" }),
  column.accessor("grade", { header: "Grade" }),
  column.accessor("inspectionRequired", {
    header: "Inspection",
    cell: (info) => (info.getValue() ? "Required" : "Not required"),
  }),
];

export default function MaterialsPage() {
  const canManage = usePermission("PRODUCT_MANAGE");
  const units = useUnits();
  const categories = useProductCategories();
  const refresh = useRefresh();
  const [adding, setAdding] = useState(false);
  return (
    <div className="space-y-6">
      <PageHeader title="Materials">
        {canManage && <Button onClick={() => setAdding(true)}>Add material</Button>}
      </PageHeader>
      <DataTable
        path="/materials"
        columns={columns}
        searchLabel="Search materials"
        emptyMessage="No materials yet."
      />
      {adding && (
        <RecordDialog
          title="Add material"
          fields={materialFields(units.options, categories)}
          record={{ inspectionRequired: true }}
          onClose={() => setAdding(false)}
          onSubmit={(values) => api.post("/materials", values).then(refresh)}
        />
      )}
    </div>
  );
}
