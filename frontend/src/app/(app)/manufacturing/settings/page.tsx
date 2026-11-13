"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Details, PageHeader, QueryState, Section, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { MODEL_LABELS } from "@/lib/manufacturing";
import type { FieldDef } from "@/lib/record-form";
import type { ManufacturingModel, ManufacturingSettings } from "@/lib/types";

const PATH = "/manufacturing/settings";

const FIELDS: FieldDef[] = [
  {
    name: "manufacturingModel",
    label: "How your organisation manufactures",
    type: "select",
    required: true,
    options: (Object.keys(MODEL_LABELS) as ManufacturingModel[]).map((value) => ({ value, label: MODEL_LABELS[value] })),
  },
  {
    name: "separateApprover",
    label: "Someone other than the submitter approves BOMs, recipes, routings and work orders",
    type: "checkbox",
  },
  { name: "workOrderRequiresBom", label: "Releasing a work order needs an approved BOM or recipe", type: "checkbox" },
  { name: "workOrderRequiresRouting", label: "Releasing a work order needs an approved routing", type: "checkbox" },
  { name: "reserveOnRelease", label: "Releasing a work order reserves its material from the oldest free lots", type: "checkbox" },
  { name: "startRequiresMaterials", label: "Production starts only when all material is reserved", type: "checkbox" },
  { name: "allowOverIssue", label: "More material than an order needs may be issued", type: "checkbox" },
];

export default function ManufacturingSettingsPage() {
  const canManage = usePermission("MANUFACTURING_MANAGE");
  const refresh = useRefresh();
  const [editing, setEditing] = useState(false);
  const { data, error } = useQuery({ queryKey: [PATH], queryFn: () => api.get<ManufacturingSettings>(PATH) });
  if (!data) return <QueryState error={error} />;
  return (
    <div className="space-y-6">
      <PageHeader title="Manufacturing settings">
        {canManage && <Button onClick={() => setEditing(true)}>Change</Button>}
      </PageHeader>
      <Section title="Rules">
        <Details
          items={[
            ["Manufacturing model", MODEL_LABELS[data.manufacturingModel]],
            ["Separate approver", data.separateApprover ? "Required" : "The submitter may approve their own"],
            ["Work order release needs a BOM or recipe", data.workOrderRequiresBom ? "Yes" : "No"],
            ["Work order release needs a routing", data.workOrderRequiresRouting ? "Yes" : "No"],
            ["Release reserves material", data.reserveOnRelease ? "Yes" : "No"],
            ["Start needs all material reserved", data.startRequiresMaterials ? "Yes" : "No, but a short start is recorded"],
            ["Issuing more than needed", data.allowOverIssue ? "Allowed" : "Refused"],
          ]}
        />
        <p className="mt-3 text-sm text-muted-foreground">
          Discrete products are built from a BOM and process products are made to a recipe. In a hybrid organisation,
          set each product&apos;s model on its page. A model cannot be dropped while products still use it.
        </p>
      </Section>
      {editing && (
        <RecordDialog
          title="Manufacturing settings"
          fields={FIELDS}
          record={data}
          onClose={() => setEditing(false)}
          onSubmit={(values) => api.put(PATH, values).then(refresh)}
        />
      )}
    </div>
  );
}
