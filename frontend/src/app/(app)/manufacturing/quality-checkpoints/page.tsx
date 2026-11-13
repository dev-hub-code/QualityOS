"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { FormError } from "@/components/forms/field";
import { PageHeader, QueryState, Section, StatusBadge, useAction, useProducts, useRefresh, useWorkCenters } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { STAGE_LABELS } from "@/lib/manufacturing";
import type { FieldDef } from "@/lib/record-form";
import type { PageResponse } from "@/lib/api";
import type { CheckpointStage, QualityCheckpoint } from "@/lib/types";

const PATH = "/manufacturing/quality-checkpoints";

type Template = { id: string; code: string; name: string; inspectionType: string; materialId: string | null; publishedVersion: number | null };

/** Published in-process and final templates that are not for one material: the ones a checkpoint can name. */
function useCheckpointTemplates() {
  const { data } = useQuery({
    queryKey: ["/inspection-templates", "checkpoints"],
    queryFn: async () => {
      const [inProcess, final] = await Promise.all(
        ["IPQC", "FQC"].map((type) => api.get<PageResponse<Template>>(`/inspection-templates?size=100&type=${type}`)),
      );
      return [...inProcess.items, ...final.items];
    },
  });
  return (data ?? [])
    .filter((template) => template.publishedVersion !== null && template.materialId === null)
    .map((template) => ({ value: template.id, label: `${template.inspectionType} · ${template.code} – ${template.name}` }));
}

const STAGES = (Object.keys(STAGE_LABELS) as CheckpointStage[]).map((value) => ({ value, label: STAGE_LABELS[value] }));

/** Where production needs an inspection. Each work order takes the checkpoints in use when it is released. */
export default function QualityCheckpointsPage() {
  const canManage = usePermission("QUALITY_CHECKPOINT_MANAGE");
  const products = useProducts();
  const workCenters = useWorkCenters();
  const templates = useCheckpointTemplates();
  const refresh = useRefresh();
  const { run, pending, message } = useAction();
  const [archived, setArchived] = useState(false);
  const [dialog, setDialog] = useState<"add" | QualityCheckpoint | null>(null);
  const { data, error } = useQuery({
    queryKey: [PATH, archived],
    queryFn: () => api.get<QualityCheckpoint[]>(`${PATH}?includeArchived=${archived}`),
  });

  const fields = (stage: boolean): FieldDef[] => [
    { name: "name", label: "Name", required: true },
    ...(stage ? [{ name: "stage", label: "When", type: "select" as const, required: true, options: STAGES }] : []),
    { name: "templateId", label: "Inspection template (in-process for operations, final for release)", type: "select", required: true, options: templates },
    { name: "productId", label: "Only for product", type: "select", options: products },
    { name: "workCenterId", label: "Only at work centre", type: "select", options: workCenters },
    { name: "operationSequence", label: "Only at operation number", type: "decimal" },
    { name: "mandatory", label: "Must pass before production goes on", type: "checkbox" },
  ];

  return (
    <div className="space-y-6">
      <PageHeader
        title="Quality checkpoints"
        subtitle="Where production needs an inspection. Work orders take the checkpoints in use when they are released."
      >
        {canManage && <Button onClick={() => setDialog("add")}>Add checkpoint</Button>}
      </PageHeader>
      <FormError message={message} />
      <label className="flex items-center gap-2 text-sm">
        <input type="checkbox" checked={archived} onChange={(event) => setArchived(event.target.checked)} />
        Show archived
      </label>
      {!data ? (
        <QueryState error={error} />
      ) : (
        <Section title={`${data.length} checkpoint${data.length === 1 ? "" : "s"}`}>
          {data.length === 0 ? (
            <p className="text-sm text-muted-foreground">No quality checkpoints yet. Without them, production runs without quality gates.</p>
          ) : (
            <ul className="divide-y text-sm">
              {data.map((checkpoint) => (
                <li key={checkpoint.id} className="flex flex-wrap items-center justify-between gap-3 py-2">
                  <span>
                    <span className="font-medium">{checkpoint.name}</span>
                    {!checkpoint.mandatory && <span className="text-muted-foreground"> (optional)</span>}
                    <span className="block text-muted-foreground">
                      {STAGE_LABELS[checkpoint.stage]} · {checkpoint.inspectionType} {checkpoint.templateCode} ·{" "}
                      {[
                        checkpoint.productCode ? `product ${checkpoint.productCode}` : "every product",
                        checkpoint.workCenterCode && `work centre ${checkpoint.workCenterCode}`,
                        checkpoint.operationSequence !== null && `operation ${checkpoint.operationSequence}`,
                      ]
                        .filter(Boolean)
                        .join(", ")}
                    </span>
                  </span>
                  <span className="flex items-center gap-2">
                    {checkpoint.archived && <StatusBadge status="ARCHIVED" />}
                    {canManage && !checkpoint.archived && (
                      <>
                        <Button size="sm" variant="outline" onClick={() => setDialog(checkpoint)}>
                          Edit
                        </Button>
                        <Button size="sm" variant="outline" disabled={pending} onClick={() => run(() => api.delete(`${PATH}/${checkpoint.id}`))}>
                          Archive
                        </Button>
                      </>
                    )}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </Section>
      )}
      {dialog === "add" && (
        <RecordDialog
          title="Add quality checkpoint"
          fields={fields(true)}
          record={{ mandatory: true }}
          onClose={() => setDialog(null)}
          onSubmit={(values) => api.post(PATH, values).then(refresh)}
        />
      )}
      {dialog !== null && dialog !== "add" && (
        <RecordDialog
          title={`Edit ${dialog.name}`}
          fields={fields(false)}
          record={{ ...dialog, operationSequence: dialog.operationSequence === null ? null : String(dialog.operationSequence) }}
          onClose={() => setDialog(null)}
          onSubmit={(values) => api.put(`${PATH}/${dialog.id}`, { ...values, stage: dialog.stage }).then(refresh)}
        />
      )}
    </div>
  );
}
