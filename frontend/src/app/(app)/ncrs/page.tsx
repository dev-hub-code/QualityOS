"use client";

import { NcrStatusBadge } from "@/components/records/ncr-status-badge";
import { createColumnHelper } from "@tanstack/react-table";
import { useQuery } from "@tanstack/react-query";
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
  useUnits,
} from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import type { FieldDef } from "@/lib/record-form";
import type { Ncr, NcrPlaces, NcrSummary } from "@/lib/types";

/** Raised by hand: anything else, a supplier's issue, or an incident at a line, work centre or machine. */
type Kind = "MANUAL" | "SUPPLIER" | "PROCESS";

const TITLES: Record<Kind, string> = {
  MANUAL: "Raise NCR",
  SUPPLIER: "Raise a supplier issue",
  PROCESS: "Raise a process incident",
};

const column = createColumnHelper<NcrSummary>();
const columns = [
  column.accessor("ncrNumber", {
    header: "Number",
    cell: (info) => (
      <RecordLink href={`/ncrs/${info.row.original.id}`}>
        {info.getValue()}
      </RecordLink>
    ),
  }),
  column.accessor("title", { header: "Title" }),
  column.accessor("severity", {
    header: "Severity",
    cell: (info) => <StatusBadge status={info.getValue()} />,
  }),
  column.accessor("status", {
    header: "Status",
    cell: (info) => <NcrStatusBadge status={info.getValue()} />,
  }),
  column.accessor("supplierName", {
    header: "Supplier",
    cell: (info) => info.getValue() ?? "—",
  }),
  column.accessor("assigneeName", {
    header: "Assigned to",
    cell: (info) => info.getValue() ?? "—",
  }),
];

const options = (list: NcrPlaces["lines"] | undefined) =>
  (list ?? []).map(({ id, label }) => ({ value: id, label }));

export default function NcrsPage() {
  const canCreate = usePermission("NCR_CREATE");
  const router = useRouter();
  const refresh = useRefresh();
  // Only someone who can see suppliers can name one, so only they are offered a supplier issue.
  const canSeeSuppliers = usePermission("SUPPLIER_VIEW");
  const suppliers = useApprovedSuppliers(canSeeSuppliers);
  const materials = useMaterials();
  const categories = useDefectCategories();
  const facilities = useFacilities();
  const units = useUnits();
  const [adding, setAdding] = useState<Kind | null>(null);
  const { data: places } = useQuery({
    queryKey: ["/ncrs/places"],
    queryFn: () => api.get<NcrPlaces>("/ncrs/places"),
    enabled: adding === "PROCESS",
  });
  const where: FieldDef[] = [
    {
      name: "productionLineId",
      label: "Production line",
      type: "select",
      options: options(places?.lines),
    },
    {
      name: "workCenterId",
      label: "Work centre",
      type: "select",
      options: options(places?.workCenters),
    },
    {
      name: "machineId",
      label: "Machine",
      type: "select",
      options: options(places?.machines),
    },
  ];
  const common: FieldDef[] = [
    { name: "title", label: "Title", required: true },
    {
      name: "description",
      label: "Description",
      type: "textarea",
      required: true,
    },
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
  ];
  const fields: FieldDef[] = [
    ...common,
    {
      name: "facilityId",
      label: "Facility",
      type: "select",
      options: facilities,
    },
    {
      name: "defectCategoryId",
      label: "Defect category",
      type: "select",
      options: categories,
    },
    {
      name: "supplierId",
      label: "Supplier",
      type: "select",
      options: suppliers,
    },
    {
      name: "materialId",
      label: "Material",
      type: "select",
      options: materials,
    },
    { name: "batchNumber", label: "Batch" },
    { name: "rejectedQuantity", label: "Rejected quantity", type: "decimal" },
    {
      name: "rejectedUomId",
      label: "Unit, if not the material's own",
      type: "select",
      options: units.options,
    },
  ];
  const fieldsFor: Record<Kind, FieldDef[]> = {
    MANUAL: fields,
    SUPPLIER: fields.map((field) =>
      field.name === "supplierId" ? { ...field, required: true } : field,
    ),
    // The plant is the one where it happened, so it is not asked for.
    PROCESS: [
      ...common,
      ...where,
      ...fields.filter((field) =>
        [
          "defectCategoryId",
          "materialId",
          "batchNumber",
          "rejectedQuantity",
          "rejectedUomId",
        ].includes(field.name),
      ),
    ],
  };
  return (
    <div className="space-y-6">
      <PageHeader title="Non-conformances">
        {canCreate && (
          <div className="flex flex-wrap gap-2">
            {canSeeSuppliers && (
              <Button variant="outline" onClick={() => setAdding("SUPPLIER")}>
                Supplier issue
              </Button>
            )}
            <Button variant="outline" onClick={() => setAdding("PROCESS")}>
              Process incident
            </Button>
            <Button onClick={() => setAdding("MANUAL")}>Raise NCR</Button>
          </div>
        )}
      </PageHeader>
      <DataTable
        path="/ncrs"
        columns={columns}
        searchLabel="Search NCRs"
        emptyMessage="No non-conformances yet."
      />
      {/* A process incident's form waits for its places, so nothing typed is lost when they arrive. */}
      {adding && (adding !== "PROCESS" || places) && (
        <RecordDialog
          title={TITLES[adding]}
          fields={fieldsFor[adding]}
          onClose={() => setAdding(null)}
          onSubmit={async (values) => {
            const created = await api.post<Ncr>("/ncrs", {
              ...values,
              source: adding,
            });
            await refresh();
            router.push(`/ncrs/${created.id}`);
          }}
        />
      )}
    </div>
  );
}
