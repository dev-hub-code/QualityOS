"use client";

import { useQuery } from "@tanstack/react-query";
import type { ColumnDef } from "@tanstack/react-table";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { DataTable } from "@/components/records/data-table";
import {
  PageHeader,
  RecordLink,
  StatusBadge,
  useApprovedSuppliers,
  useAssignees,
  useFacilities,
  useProducts,
} from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import type { FieldDef } from "@/lib/record-form";
import type { Audit, AuditSummary, ChecklistSummary } from "@/lib/types";
import { lookupAll } from "@/components/records/parts";

const TYPES = ["INTERNAL", "SUPPLIER", "PROCESS", "PRODUCT"].map((value) => ({
  value,
  label: value[0] + value.slice(1).toLowerCase(),
}));

const COLUMNS: ColumnDef<AuditSummary>[] = [
  {
    header: "Audit",
    cell: ({ row }) => <RecordLink href={`/audits/${row.original.id}`}>{row.original.auditNumber}</RecordLink>,
  },
  { header: "Title", accessorKey: "title" },
  { header: "Type", cell: ({ row }) => row.original.auditType.toLowerCase() },
  { header: "Planned", accessorKey: "plannedOn" },
  { header: "Lead auditor", accessorKey: "leadAuditorName" },
  {
    header: "Status",
    cell: ({ row }) => <StatusBadge status={row.original.overdue ? "OVERDUE" : row.original.status} />,
  },
];

/** Closing a repeating audit plans the next one this many months after its planned date. Blank is a one-off. */
const REPEATS = [1, 3, 6, 12, 24].map((months) => ({
  value: String(months),
  label: months === 1 ? "Month" : months === 12 ? "Year" : months === 24 ? "Two years" : `${months} months`,
}));

export default function AuditsPage() {
  const router = useRouter();
  const canManage = usePermission("AUDIT_MANAGE");
  const facilities = useFacilities();
  const assignees = useAssignees();
  const suppliers = useApprovedSuppliers();
  const products = useProducts();
  const [planning, setPlanning] = useState(false);
  const { data: checklists } = useQuery({
    queryKey: ["/audit-checklists", "options"],
    queryFn: () => lookupAll<ChecklistSummary>("/audit-checklists"),
    enabled: planning,
  });
  const fields: FieldDef[] = [
    { name: "auditType", label: "Type", type: "select", required: true, options: TYPES },
    { name: "title", label: "Title", required: true },
    { name: "scope", label: "Scope", type: "textarea", required: true },
    { name: "facilityId", label: "Facility", type: "select", required: true, options: facilities },
    { name: "supplierId", label: "Supplier, for a supplier audit", type: "select", options: suppliers },
    { name: "productId", label: "Product, for a product audit", type: "select", options: products },
    {
      name: "checklistId",
      label: "Checklist",
      type: "select",
      required: true,
      options: (checklists ?? []).map((item) => ({ value: item.id, label: `${item.code} – ${item.name}` })),
    },
    { name: "plannedOn", label: "Planned for", type: "date", required: true },
    { name: "leadAuditorId", label: "Lead auditor", type: "select", required: true, options: assignees },
    { name: "repeatEveryMonths", label: "Repeat every", type: "select", options: REPEATS },
  ];
  return (
    <div className="space-y-6">
      <PageHeader title="Audits">{canManage && <Button onClick={() => setPlanning(true)}>Plan audit</Button>}</PageHeader>
      <DataTable path="/audits" columns={COLUMNS} searchLabel="Search audits" emptyMessage="No audits." />
      {planning && checklists && (
        <RecordDialog
          title="Plan audit"
          fields={fields}
          onClose={() => setPlanning(false)}
          onSubmit={async (values) => {
            const audit = await api.post<Audit>("/audits", values);
            router.push(`/audits/${audit.id}`);
          }}
        />
      )}
    </div>
  );
}
