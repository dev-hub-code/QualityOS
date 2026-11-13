"use client";

import { useQuery } from "@tanstack/react-query";
import { useParams } from "next/navigation";
import { useState } from "react";
import { FormError } from "@/components/forms/field";
import { Details, PageHeader, QueryState, RecordLink, Section, StatusBadge, useAction, useRefresh, useUnits } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api, type PageResponse } from "@/lib/api";
import { reinspectionState, reworkActions } from "@/lib/manufacturing";
import { formatQuantity } from "@/lib/record-form";
import type { ReworkDetail } from "@/lib/types";

const when = (value: string | null) => (value ? new Date(value).toLocaleString() : "–");
const words = (value: string | null) => (value ? value.replaceAll("_", " ").toLowerCase() : "–");

type Dialog = "approve" | "complete" | "reinspect" | "cancel" | { step: string } | null;
type Template = { id: string; code: string; name: string; inspectionType: string; publishedVersion: number | null };

/** Published in-process and final templates, to reinspect with. */
function useBatchTemplates(enabled: boolean) {
  const { data } = useQuery({
    queryKey: ["/inspection-templates", "batch"],
    enabled,
    queryFn: async () => {
      const lists = await Promise.all(
        ["IPQC", "FQC"].map((type) => api.get<PageResponse<Template>>(`/inspection-templates?size=100&type=${type}`)),
      );
      return lists.flatMap((list) => list.items);
    },
  });
  return (data ?? [])
    .filter((template) => template.publishedVersion !== null)
    .map((template) => ({ value: template.id, label: `${template.inspectionType} · ${template.code} – ${template.name}` }));
}

export default function ReworkOrderPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/manufacturing/rework-orders/${id}`;
  const can = {
    manage: usePermission("REWORK_MANAGE"),
    approve: usePermission("REWORK_APPROVE"),
    execute: usePermission("PRODUCTION_EXECUTE"),
    inspect: usePermission("INSPECTION_CREATE"),
  };
  const units = useUnits();
  const refresh = useRefresh();
  const { run, pending, message } = useAction();
  const [dialog, setDialog] = useState<Dialog>(null);
  const templates = useBatchTemplates(dialog === "reinspect");
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<ReworkDetail>(path) });
  if (!data) return <QueryState error={error} />;

  const { rework } = data;
  const unit = units.label(rework.uomId);
  const actions = reworkActions({ status: rework.status, ...reinspectionState(data.reinspection) }, can);
  const close = () => setDialog(null);

  return (
    <div className="space-y-6">
      <PageHeader
        title={rework.reworkNumber}
        subtitle={
          <span className="flex flex-wrap items-center gap-2">
            <StatusBadge status={rework.status} />
            {formatQuantity(rework.quantity)} {unit} of {rework.productCode} – {rework.productName}
          </span>
        }
      >
        {actions.includes("approve") && <Button onClick={() => setDialog("approve")}>Approve</Button>}
        {actions.includes("start") && (
          <Button disabled={pending} onClick={() => run(() => api.post(`${path}/start`))}>
            Start rework
          </Button>
        )}
        {actions.includes("complete") && <Button onClick={() => setDialog("complete")}>Complete</Button>}
        {actions.includes("reinspect") && <Button onClick={() => setDialog("reinspect")}>Reinspect</Button>}
        {actions.includes("close") && (
          <Button disabled={pending} onClick={() => run(() => api.post(`${path}/close`))}>
            Close on the reinspection
          </Button>
        )}
        {actions.includes("cancel") && (
          <Button variant="destructive" onClick={() => setDialog("cancel")}>
            Cancel
          </Button>
        )}
      </PageHeader>
      <FormError message={message} />
      {rework.status === "CANCELLED" && data.statusReason && (
        <p className="rounded-xl bg-amber-50 px-3 py-2 text-sm text-amber-900 ring-1 ring-amber-200">Cancelled: {data.statusReason}</p>
      )}
      <Section title="Details">
        <Details
          items={[
            ["Work order", <RecordLink key="order" href={`/manufacturing/work-orders/${rework.workOrderId}`}>{rework.workOrderNumber}</RecordLink>],
            [
              "Batch",
              <RecordLink key="batch" href={`/production-batches/${rework.productionBatchId}`}>
                {rework.batchNumber ?? "Batch"}
              </RecordLink>,
            ],
            ["From operation", rework.operationSequence === null ? "–" : String(rework.operationSequence)],
            [
              "Inspection",
              data.sourceInspectionId ? (
                <RecordLink key="source" href={`/inspections/${data.sourceInspectionId}`}>
                  {data.sourceInspectionNumber ?? "Inspection"}
                </RecordLink>
              ) : (
                "–"
              ),
            ],
            ["NCR", rework.ncrId ? <RecordLink key="ncr" href={`/ncrs/${rework.ncrId}`}>{rework.ncrNumber ?? "NCR"}</RecordLink> : "–"],
            ["Reason", data.reason],
            ["Instructions", data.instructions ?? "–"],
            ["Approved", data.approvedAt ? `${data.approvedByName ?? "Former member"}, ${when(data.approvedAt)}` : "–"],
            ["Started", when(data.startedAt)],
            [
              "Result",
              data.completedAt
                ? `Reworked ${formatQuantity(data.reworkedQuantity)} ${unit}, scrapped ${formatQuantity(data.scrappedQuantity)} ${unit}`
                : "–",
            ],
            [
              "Reinspection",
              data.reinspection ? (
                <span key="reinspection">
                  <RecordLink href={`/inspections/${data.reinspection.id}`}>{data.reinspection.inspectionNumber}</RecordLink> ·{" "}
                  {words(data.reinspection.disposition ?? data.reinspection.status)}
                </span>
              ) : (
                "–"
              ),
            ],
            ["Outcome", words(rework.finalDisposition)],
          ]}
        />
      </Section>
      <Section title="Steps">
        <ol className="divide-y text-sm">
          {data.steps.map((step) => (
            <li key={step.id} className="flex flex-wrap items-center justify-between gap-3 py-2">
              <span>
                {step.sequence}. {step.description}
                {step.workCenterCode && <span className="text-muted-foreground"> at {step.workCenterCode}</span>}
                {step.completedAt && (
                  <span className="block text-muted-foreground">
                    Done by {step.completedByName ?? "Former member"}, {when(step.completedAt)}
                    {step.note && ` · ${step.note}`}
                  </span>
                )}
              </span>
              {step.completedAt ? (
                <StatusBadge status="DONE" />
              ) : (
                rework.status === "IN_PROGRESS" &&
                can.execute && (
                  <Button size="sm" variant="outline" onClick={() => setDialog({ step: step.id })}>
                    Mark done
                  </Button>
                )
              )}
            </li>
          ))}
        </ol>
      </Section>
      <Section title="Status history">
        <ul className="divide-y text-sm">
          {data.history.map((change) => (
            <li key={`${change.changedAt}-${change.toStatus}`} className="flex flex-wrap items-center justify-between gap-3 py-2">
              <span className="flex items-center gap-2">
                <StatusBadge status={change.toStatus} />
                {change.reason}
              </span>
              <span className="text-muted-foreground">
                {change.changedByName ?? "Former member"} · {when(change.changedAt)}
              </span>
            </li>
          ))}
        </ul>
      </Section>
      {dialog === "approve" && (
        <RecordDialog
          title={`Approve ${rework.reworkNumber}`}
          fields={[{ name: "note", label: "Note (needed if you are approving your own rework order)", type: "textarea" }]}
          onClose={close}
          onSubmit={(values) => api.post(`${path}/approve`, values).then(refresh)}
        />
      )}
      {dialog === "complete" && (
        <RecordDialog
          title="Complete the rework"
          fields={[
            { name: "reworkedQuantity", label: `Reworked (${unit})`, type: "decimal", required: true },
            { name: "scrappedQuantity", label: `Scrapped (${unit})`, type: "decimal", required: true },
          ]}
          record={{ reworkedQuantity: formatQuantity(rework.quantity), scrappedQuantity: "0" }}
          onClose={close}
          onSubmit={(values) => api.post(`${path}/complete`, values).then(refresh)}
        />
      )}
      {dialog === "reinspect" && (
        <RecordDialog
          title="Reinspect the reworked goods"
          fields={[{ name: "templateId", label: "Template (left blank, the one that fits the batch)", type: "select", options: templates }]}
          onClose={close}
          onSubmit={(values) => api.post(`${path}/reinspect`, values).then(refresh)}
        />
      )}
      {dialog === "cancel" && (
        <RecordDialog
          title={`Cancel ${rework.reworkNumber}`}
          fields={[{ name: "reason", label: "Why it is cancelled", type: "textarea", required: true }]}
          onClose={close}
          onSubmit={(values) => api.post(`${path}/cancel`, values).then(refresh)}
        />
      )}
      {dialog !== null && typeof dialog === "object" && (
        <RecordDialog
          title="Mark the step done"
          fields={[{ name: "note", label: "Note", type: "textarea" }]}
          onClose={close}
          onSubmit={(values) => api.post(`${path}/steps/${dialog.step}/complete`, values).then(refresh)}
        />
      )}
    </div>
  );
}
