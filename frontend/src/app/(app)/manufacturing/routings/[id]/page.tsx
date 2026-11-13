"use client";

import { useQuery } from "@tanstack/react-query";
import { useParams } from "next/navigation";
import { useState } from "react";
import { FormError } from "@/components/forms/field";
import { RevisionHeader } from "@/components/manufacturing/revision-header";
import { QueryState, Section, useAction, useRefresh, useUnits, useWorkCenters } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { describeLimits } from "@/lib/manufacturing";
import { formatQuantity, type FieldDef } from "@/lib/record-form";
import type { RoutingDetail, RoutingOperation } from "@/lib/types";

const PARAMETER_FIELDS: FieldDef[] = [
  { name: "code", label: "Code", required: true },
  { name: "name", label: "Name", required: true },
  { name: "unit", label: "Unit, such as rpm or °C" },
  { name: "target", label: "Target", type: "decimal" },
  { name: "minimum", label: "Lower limit", type: "decimal" },
  { name: "maximum", label: "Upper limit", type: "decimal" },
];

export default function RoutingPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/manufacturing/routings/${id}`;
  const canManage = usePermission("ROUTING_MANAGE");
  const workCenters = useWorkCenters();
  const units = useUnits();
  const refresh = useRefresh();
  const { run, pending, message } = useAction();
  const [editing, setEditing] = useState<RoutingOperation | "new" | null>(null);
  const [parameterFor, setParameterFor] = useState<string | null>(null);
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<RoutingDetail>(path) });
  if (!data) return <QueryState error={error} />;

  const { revision, operations } = data;
  const unit = units.label(revision.productUomId);
  const draft = canManage && revision.status === "DRAFT";
  const next = (operations.at(-1)?.sequence ?? 0) + 10;
  const operationFields: FieldDef[] = [
    { name: "sequence", label: "Step number", type: "decimal", required: true },
    { name: "name", label: "Operation", required: true },
    { name: "workCenterId", label: "Work centre", type: "select", required: true, options: workCenters },
    { name: "setupMinutes", label: "Setup time (minutes)", type: "decimal" },
    { name: "runMinutes", label: `Run time per ${unit || "unit"} (minutes)`, type: "decimal" },
    { name: "instructions", label: "Instructions", type: "textarea" },
  ];

  return (
    <div className="space-y-6">
      <RevisionHeader kind="ROUTING" revision={revision} unit={unit} />
      <Section
        title="Operations"
        action={
          draft && (
            <Button variant="outline" size="sm" onClick={() => setEditing("new")}>
              Add operation
            </Button>
          )
        }
      >
        <FormError message={message} />
        {operations.length === 0 && <p className="text-sm text-muted-foreground">No operations yet.</p>}
        <ol className="space-y-3 text-sm">
          {operations.map((operation) => (
            <li key={operation.id} className="rounded-xl border bg-white/60 p-3">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="font-medium">
                  {operation.sequence}. {operation.name}
                  <span className="font-normal text-muted-foreground">
                    {" "}
                    at {operation.workCenterCode} – {operation.workCenterName}
                  </span>
                </span>
                {draft && (
                  <span className="flex gap-1">
                    <Button variant="ghost" size="sm" onClick={() => setParameterFor(operation.id)}>
                      Add parameter
                    </Button>
                    <Button variant="ghost" size="sm" onClick={() => setEditing(operation)}>
                      Edit
                    </Button>
                    <Button variant="ghost" size="sm" disabled={pending} onClick={() => run(() => api.delete(`${path}/operations/${operation.id}`))}>
                      Remove
                    </Button>
                  </span>
                )}
              </div>
              <p className="mt-1 text-muted-foreground">
                Setup {formatQuantity(operation.setupMinutes)} min · run {formatQuantity(operation.runMinutes)} min per {unit || "unit"}
              </p>
              {operation.instructions && <p className="mt-1 whitespace-pre-line">{operation.instructions}</p>}
              {operation.parameters.length > 0 && (
                <ul className="mt-2 space-y-1">
                  {operation.parameters.map((parameter) => (
                    <li key={parameter.id} className="flex flex-wrap items-center justify-between gap-2">
                      <span>
                        {parameter.code} – {parameter.name}: {describeLimits(parameter)}
                      </span>
                      {draft && (
                        <Button
                          variant="ghost"
                          size="sm"
                          disabled={pending}
                          onClick={() => run(() => api.delete(`${path}/operations/${operation.id}/parameters/${parameter.id}`))}
                        >
                          Remove
                        </Button>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </li>
          ))}
        </ol>
      </Section>
      {editing && (
        <RecordDialog
          title={editing === "new" ? "Add operation" : `Edit step ${editing.sequence}`}
          fields={operationFields}
          record={editing === "new" ? { sequence: String(next) } : { ...editing, sequence: String(editing.sequence) }}
          onClose={() => setEditing(null)}
          onSubmit={(values) => {
            const body = { ...values, sequence: Number(values.sequence) };
            return (
              editing === "new" ? api.post(`${path}/operations`, body) : api.put(`${path}/operations/${editing.id}`, body)
            ).then(refresh);
          }}
        />
      )}
      {parameterFor && (
        <RecordDialog
          title="Add process parameter"
          fields={PARAMETER_FIELDS}
          onClose={() => setParameterFor(null)}
          onSubmit={(values) => api.post(`${path}/operations/${parameterFor}/parameters`, values).then(refresh)}
        />
      )}
    </div>
  );
}
