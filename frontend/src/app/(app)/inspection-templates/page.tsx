"use client";

import { createColumnHelper } from "@tanstack/react-table";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { DataTable } from "@/components/records/data-table";
import { PageHeader, RecordLink, useMaterials, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { templateFields } from "@/lib/record-fields";
import type { TemplateDetail, TemplateSummary } from "@/lib/types";

const column = createColumnHelper<TemplateSummary>();
const columns = [
  column.accessor("code", {
    header: "Code",
    cell: (info) => (
      <RecordLink href={`/inspection-templates/${info.row.original.id}`}>{info.getValue()}</RecordLink>
    ),
  }),
  column.accessor("name", { header: "Name" }),
  column.accessor("inspectionType", { header: "Type" }),
  column.accessor("publishedVersion", {
    header: "Published",
    cell: (info) => (info.getValue() ? `Version ${info.getValue()}` : "Not published"),
  }),
];

export default function InspectionTemplatesPage() {
  const canManage = usePermission("TEMPLATE_MANAGE");
  const materials = useMaterials();
  const refresh = useRefresh();
  const router = useRouter();
  const [adding, setAdding] = useState(false);
  return (
    <div className="space-y-6">
      <PageHeader title="Inspection templates">
        {canManage && <Button onClick={() => setAdding(true)}>Add template</Button>}
      </PageHeader>
      <DataTable
        path="/inspection-templates"
        columns={columns}
        searchLabel="Search templates"
        emptyMessage="No templates yet."
      />
      {adding && (
        <RecordDialog
          title="Add template"
          fields={templateFields(materials)}
          onClose={() => setAdding(false)}
          onSubmit={async (values) => {
            const created = await api.post<TemplateDetail>("/inspection-templates", values);
            await refresh();
            router.push(`/inspection-templates/${created.template.id}`);
          }}
        />
      )}
    </div>
  );
}
