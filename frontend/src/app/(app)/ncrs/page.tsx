"use client";

import { createColumnHelper } from "@tanstack/react-table";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { DataTable } from "@/components/records/data-table";
import {
  PageHeader,
  RecordLink,
  StatusBadge,
  useApprovedSuppliers,
  useFacilities,
  useDefectCategories,
  useMaterials,
  useRefresh,
} from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import type { FieldDef } from "@/lib/record-form";
import type { Ncr, NcrSummary } from "@/lib/types";

const column = createColumnHelper<NcrSummary>();
const columns = [
  column.accessor("ncrNumber", {
    header: "Number",
    cell: (info) => <RecordLink href={`/ncrs/${info.row.original.id}`}>{info.getValue()}</RecordLink>,
  }),
  column.accessor("title", { header: "Title" }),
  column.accessor("severity", { header: "Severity", cell: (info) => <StatusBadge status={info.getValue()} /> }),
  column.accessor("status", { header: "Status", cell: (info) => <StatusBadge status={info.getValue()} /> }),
  column.accessor("supplierName", { header: "Supplier", cell: (info) => info.getValue() ?? "—" }),
  column.accessor("assigneeName", { header: "Assigned to", cell: (info) => info.getValue() ?? "—" }),
];

export default function NcrsPage() {
  const canCreate = usePermission("NCR_CREATE");
  const router = useRouter();
  const refresh = useRefresh();
  const suppliers = useApprovedSuppliers();
  const materials = useMaterials();
  const categories = useDefectCategories();
  const facilities = useFacilities();
  const [adding, setAdding] = useState(false);
  const fields: FieldDef[] = [
    { name: "title", label: "Title", required: true },
    { name: "description", label: "Description", type: "textarea", required: true },
    {
      name: "severity",
      label: "Severity",
      type: "select",
      required: true,
      options: [
        { value: "CRITICAL", label: "Critical" },
        { value: "MAJOR", label: "Major" },
        { value: "MINOR", label: "Minor" },
      ],
    },
    { name: "facilityId", label: "Facility", type: "select", options: facilities },
    { name: "defectCategoryId", label: "Defect category", type: "select", options: categories },
    { name: "supplierId", label: "Supplier", type: "select", options: suppliers },
    { name: "materialId", label: "Material", type: "select", options: materials },
    { name: "batchNumber", label: "Batch" },
    { name: "rejectedQuantity", label: "Rejected quantity", type: "decimal" },
  ];
  return (
    <div className="space-y-6">
      <PageHeader title="Non-conformances">
        {canCreate && <Button onClick={() => setAdding(true)}>Raise NCR</Button>}
      </PageHeader>
      <DataTable path="/ncrs" columns={columns} searchLabel="Search NCRs" emptyMessage="No non-conformances yet." />
      {adding && (
        <RecordDialog
          title="Raise NCR"
          fields={fields}
          onClose={() => setAdding(false)}
          onSubmit={async (values) => {
            const created = await api.post<Ncr>("/ncrs", values);
            await refresh();
            router.push(`/ncrs/${created.id}`);
          }}
        />
      )}
    </div>
  );
}
