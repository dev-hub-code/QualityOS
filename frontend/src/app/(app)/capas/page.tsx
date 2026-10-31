"use client";

import { createColumnHelper } from "@tanstack/react-table";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { DataTable } from "@/components/records/data-table";
import { PageHeader, RecordLink, StatusBadge, useAssignees, useFacilities, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import type { FieldDef } from "@/lib/record-form";
import type { Capa, CapaSummary } from "@/lib/types";

const column = createColumnHelper<CapaSummary>();
const columns = [
  column.accessor("capaNumber", {
    header: "Number",
    cell: (info) => <RecordLink href={`/capas/${info.row.original.id}`}>{info.getValue()}</RecordLink>,
  }),
  column.accessor("title", { header: "Title" }),
  column.accessor("status", { header: "Status", cell: (info) => <StatusBadge status={info.getValue()} /> }),
  column.accessor("ownerName", { header: "Owner", cell: (info) => info.getValue() ?? "—" }),
  column.accessor("targetDate", {
    header: "Target",
    cell: (info) => (
      <span className="flex items-center gap-2">
        {info.getValue()}
        {info.row.original.overdue && <StatusBadge status="OVERDUE" />}
      </span>
    ),
  }),
];

export default function CapasPage() {
  const canManage = usePermission("CAPA_MANAGE");
  const router = useRouter();
  const refresh = useRefresh();
  const assignees = useAssignees();
  const facilities = useFacilities();
  const [adding, setAdding] = useState(false);
  const fields: FieldDef[] = [
    { name: "title", label: "Title", required: true },
    { name: "problemStatement", label: "Problem statement", type: "textarea", required: true },
    { name: "ownerId", label: "Owner", type: "select", required: true, options: assignees },
    { name: "facilityId", label: "Facility", type: "select", options: facilities },
    { name: "targetDate", label: "Target date", type: "date", required: true },
    { name: "effectivenessRequired", label: "Requires effectiveness verification", type: "checkbox" },
  ];
  return (
    <div className="space-y-6">
      <PageHeader title="Corrective and preventive actions">
        {canManage && <Button onClick={() => setAdding(true)}>Add CAPA</Button>}
      </PageHeader>
      <DataTable path="/capas" columns={columns} searchLabel="Search CAPAs" emptyMessage="No CAPAs yet." />
      {adding && (
        <RecordDialog
          title="Add CAPA"
          fields={fields}
          record={{ effectivenessRequired: true }}
          onClose={() => setAdding(false)}
          onSubmit={async (values) => {
            const created = await api.post<Capa>("/capas", values);
            await refresh();
            router.push(`/capas/${created.id}`);
          }}
        />
      )}
    </div>
  );
}
