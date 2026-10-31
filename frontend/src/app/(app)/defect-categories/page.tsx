"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { FormError } from "@/components/forms/field";
import { PageHeader, useAction, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import type { FieldDef } from "@/lib/record-form";
import type { DefectCategory } from "@/lib/types";

const FIELDS: FieldDef[] = [
  { name: "code", label: "Code", required: true },
  { name: "name", label: "Name", required: true },
];
const PATH = "/defect-categories";

export default function DefectCategoriesPage() {
  const canManage = usePermission("NCR_ASSIGN");
  const refresh = useRefresh();
  const { run, message } = useAction();
  const [adding, setAdding] = useState(false);
  const { data = [] } = useQuery({ queryKey: [PATH], queryFn: () => api.get<DefectCategory[]>(PATH) });
  return (
    <div className="max-w-2xl space-y-6">
      <PageHeader title="Defect categories">
        {canManage && <Button onClick={() => setAdding(true)}>Add category</Button>}
      </PageHeader>
      <FormError message={message} />
      {data.length === 0 && <p className="text-sm text-muted-foreground">No categories yet.</p>}
      <ul className="divide-y rounded-lg border text-sm">
        {data.map((category) => (
          <li key={category.id} className="flex items-center justify-between gap-3 p-3">
            <span>
              {category.code} – {category.name}
            </span>
            {canManage && (
              <Button variant="ghost" size="sm" onClick={() => run(() => api.delete(`${PATH}/${category.id}`))}>
                Archive
              </Button>
            )}
          </li>
        ))}
      </ul>
      {adding && (
        <RecordDialog
          title="Add category"
          fields={FIELDS}
          onClose={() => setAdding(false)}
          onSubmit={(values) => api.post(PATH, values).then(refresh)}
        />
      )}
    </div>
  );
}
