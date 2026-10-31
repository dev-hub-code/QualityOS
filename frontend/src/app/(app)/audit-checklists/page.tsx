"use client";

import type { ColumnDef } from "@tanstack/react-table";
import { useState } from "react";
import { formatChecklist, parseChecklist } from "@/components/audits/checklist-text";
import { DataTable } from "@/components/records/data-table";
import { PageHeader, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import type { FieldDef } from "@/lib/record-form";
import type { Checklist, ChecklistSummary } from "@/lib/types";

const FIELDS: FieldDef[] = [
  { name: "code", label: "Code", required: true },
  { name: "name", label: "Name", required: true },
  { name: "questions", label: "Questions, one a line. Add a clause after a bar: Question | 7.1.5", type: "textarea", required: true },
];

export default function AuditChecklistsPage() {
  const canManage = usePermission("AUDIT_MANAGE");
  const refresh = useRefresh();
  const [editing, setEditing] = useState<(Partial<Checklist> & { questions?: string }) | null>(null);
  const open = async (id: string) => {
    const checklist = await api.get<Checklist>(`/audit-checklists/${id}`);
    setEditing({ ...checklist, questions: formatChecklist(checklist.items) });
  };
  const columns: ColumnDef<ChecklistSummary>[] = [
    { header: "Code", accessorKey: "code" },
    { header: "Name", accessorKey: "name" },
    ...(canManage
      ? [
          {
            header: "Actions",
            cell: ({ row }) => (
              <Button variant="ghost" size="sm" onClick={() => open(row.original.id)}>
                Edit
              </Button>
            ),
          } as ColumnDef<ChecklistSummary>,
        ]
      : []),
  ];
  return (
    <div className="space-y-6">
      <PageHeader title="Audit checklists" subtitle="A change applies to audits planned afterwards.">
        {canManage && <Button onClick={() => setEditing({})}>New checklist</Button>}
      </PageHeader>
      <DataTable path="/audit-checklists" columns={columns} searchLabel="Search checklists" emptyMessage="No checklists." />
      {editing && (
        <RecordDialog
          title={editing.id ? "Edit checklist" : "New checklist"}
          fields={FIELDS}
          record={editing}
          onClose={() => setEditing(null)}
          onSubmit={async ({ code, name, questions }) => {
            const body = { code, name, items: parseChecklist(String(questions)) };
            await (editing.id ? api.put(`/audit-checklists/${editing.id}`, body) : api.post("/audit-checklists", body));
            refresh();
          }}
        />
      )}
    </div>
  );
}
