"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useParams } from "next/navigation";
import { InspectionInstruments } from "@/components/calibration/inspection-instruments";
import { useState } from "react";
import { FormError } from "@/components/forms/field";
import { AttachmentPanel } from "@/components/records/attachment-panel";
import { Details, PageHeader, QueryState, RecordLink, Section, StatusBadge, useAction, useRefresh, useUnits } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useMe, usePermission } from "@/hooks/use-me";
import { api, type PageResponse } from "@/lib/api";
import { requirement, sampleKey, samplesOf, toEntries, toFormValues } from "@/lib/inspection";
import { formatQuantity, type FieldDef } from "@/lib/record-form";
import type { Attachment, Inspection, NcrSummary, ParameterSpec } from "@/lib/types";

const NOTE_FIELD: FieldDef = { name: "note", label: "Note" };
const SEND_BACK_FIELDS: FieldDef[] = [{ ...NOTE_FIELD, label: "What needs correcting", required: true }];
const SELECT_CLASS = "h-11 w-full rounded-md border border-input bg-transparent px-2 text-base";

function SampleInput({
  parameter,
  id,
  value,
  disabled,
  onChange,
}: {
  parameter: ParameterSpec;
  id: string;
  value: string;
  disabled: boolean;
  onChange: (value: string) => void;
}) {
  if (parameter.dataType === "BOOLEAN") {
    return (
      <select id={id} className={SELECT_CLASS} value={value} disabled={disabled} onChange={(e) => onChange(e.target.value)}>
        <option value="">Select…</option>
        <option value="true">Yes</option>
        <option value="false">No</option>
      </select>
    );
  }
  return (
    <Input
      id={id}
      className="h-11 text-base"
      type={parameter.dataType === "DATE" ? "date" : "text"}
      inputMode={parameter.dataType === "NUMERIC" ? "decimal" : undefined}
      value={value}
      disabled={disabled}
      onChange={(event) => onChange(event.target.value)}
    />
  );
}

function Evidence({ inspectionId, parameter, attachments }: { inspectionId: string; parameter: ParameterSpec; attachments: Attachment[] }) {
  const { run, pending, message } = useAction();
  const count = attachments.filter((attachment) => attachment.category === parameter.code).length;
  return (
    <div className="mt-3 space-y-1.5">
      <FormError message={message} />
      <Label htmlFor={`evidence-${parameter.code}`}>Evidence for {parameter.code} ({count} added)</Label>
      <Input
        id={`evidence-${parameter.code}`}
        type="file"
        accept="image/*,application/pdf"
        capture="environment"
        disabled={pending}
        onChange={(event) => {
          const file = event.target.files?.[0];
          if (!file) return;
          const form = new FormData();
          form.set("file", file);
          run(() =>
            api.upload(`/attachments?entityType=INSPECTION&entityId=${inspectionId}&category=${encodeURIComponent(parameter.code)}`, form),
          );
        }}
      />
    </div>
  );
}

function Execution({ inspection, editable }: { inspection: Inspection; editable: boolean }) {
  const units = useUnits();
  const path = `/inspections/${inspection.id}`;
  const attachmentsPath = `/attachments?entityType=INSPECTION&entityId=${inspection.id}`;
  const [values, setValues] = useState(() => toFormValues(inspection.results));
  const [formError, setFormError] = useState("");
  const { run, pending, message } = useAction();
  const { data: attachments = [] } = useQuery({
    queryKey: [attachmentsPath],
    queryFn: () => api.get<Attachment[]>(attachmentsPath),
  });
  const { parameters } = inspection.specification;

  /** Saving first means that nothing typed can be lost on submission. */
  function save(thenSubmit: boolean) {
    const { entries, invalid } = toEntries(parameters, inspection.sampleSize, values);
    if (invalid.length > 0) {
      setFormError(`Check these values: ${invalid.join(", ")}.`);
      return;
    }
    setFormError("");
    run(async () => {
      if (entries.length > 0) await api.post(`${path}/execute`, { results: entries });
      if (thenSubmit) await api.post(`${path}/submit`);
    });
  }

  return (
    <div className="space-y-4">
      <InspectionInstruments inspection={inspection} editable={editable} />
      {parameters.map((parameter) => (
        <Section
          key={parameter.code}
          title={parameter.name}
          action={parameter.criticality === "CRITICAL" ? <StatusBadge status="HOLD" /> : undefined}
        >
          <p className="mb-3 text-sm text-muted-foreground">
            {parameter.code} · {requirement(parameter)} {units.label(parameter.uomId)}
            {parameter.inspectionMethod && ` · ${parameter.inspectionMethod}`}
            {parameter.criticality === "CRITICAL" && " · critical"}
          </p>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            {Array.from({ length: samplesOf(parameter, inspection.sampleSize) }, (_, index) => index + 1).map((sample) => {
              const key = sampleKey(parameter.code, sample);
              const saved = inspection.results.find(
                (result) => result.parameterCode === parameter.code && result.sampleNumber === sample,
              );
              const id = `value-${parameter.code}-${sample}`;
              return (
                <div key={key} className="space-y-1.5">
                  <Label htmlFor={id}>
                    {parameter.code} sample {sample}
                  </Label>
                  <SampleInput
                    parameter={parameter}
                    id={id}
                    value={values[key] ?? ""}
                    disabled={!editable}
                    onChange={(value) => setValues((current) => ({ ...current, [key]: value }))}
                  />
                  {saved && <StatusBadge status={saved.evaluation} />}
                </div>
              );
            })}
          </div>
          {editable && parameter.evidenceRequired && (
            <Evidence inspectionId={inspection.id} parameter={parameter} attachments={attachments} />
          )}
        </Section>
      ))}
      <FormError message={formError || message} />
      {editable && (
        <div className="sticky bottom-0 flex gap-3 border-t bg-background py-3">
          <Button variant="outline" className="h-11 flex-1 sm:flex-none" disabled={pending} onClick={() => save(false)}>
            Save
          </Button>
          <Button className="h-11 flex-1 sm:flex-none" disabled={pending} onClick={() => save(true)}>
            Submit for approval
          </Button>
        </div>
      )}
    </div>
  );
}

type Dialog = "approve" | "send-back" | "disposition" | null;

function Review({ inspection }: { inspection: Inspection }) {
  const path = `/inspections/${inspection.id}`;
  const { data: me } = useMe();
  const canApprove = usePermission("INSPECTION_APPROVE");
  const canDispose = usePermission("DISPOSITION_RECORD");
  const refresh = useRefresh();
  const [dialog, setDialog] = useState<Dialog>(null);
  const { status, finalResult, evaluatedResult } = inspection;
  const inspector = inspection.decisions.findLast((decision) => decision.decision === "SUBMITTED")?.decidedBy;
  const independent = me?.user.id !== inspector;

  const dispositions =
    finalResult === "PASS"
      ? [
          { value: "ACCEPT", label: "Accept" },
          { value: "REJECT", label: "Reject" },
          { value: "HOLD", label: "Hold" },
          { value: "REWORK", label: "Rework" },
        ]
      : [
          { value: "REJECT", label: "Reject" },
          { value: "HOLD", label: "Hold" },
          { value: "REWORK", label: "Rework" },
          { value: "CONDITIONAL_ACCEPTANCE", label: "Conditional acceptance" },
        ];
  const approveFields: FieldDef[] = [
    ...(evaluatedResult === "PENDING_REVIEW"
      ? [
          {
            name: "result",
            label: "Result",
            type: "select" as const,
            required: true,
            options: [
              { value: "PASS", label: "Pass" },
              { value: "FAIL", label: "Fail" },
            ],
          },
        ]
      : []),
    NOTE_FIELD,
  ];

  return (
    <>
      {status === "SUBMITTED" && canApprove && !independent && (
        <p className="text-sm text-muted-foreground">Someone other than the inspector must review this inspection.</p>
      )}
      {status === "SUBMITTED" && canApprove && independent && (
        <div className="flex flex-wrap gap-3">
          <Button onClick={() => setDialog("approve")}>Approve</Button>
          <Button variant="outline" onClick={() => setDialog("send-back")}>
            Send back
          </Button>
        </div>
      )}
      {status === "APPROVED" && canDispose && <Button onClick={() => setDialog("disposition")}>Record disposition</Button>}
      {dialog === "approve" && (
        <RecordDialog
          title="Approve inspection"
          fields={approveFields}
          onClose={() => setDialog(null)}
          onSubmit={(values) => api.post(`${path}/approve`, values).then(refresh)}
        />
      )}
      {dialog === "send-back" && (
        <RecordDialog
          title="Send back to inspector"
          fields={SEND_BACK_FIELDS}
          onClose={() => setDialog(null)}
          onSubmit={(values) => api.post(`${path}/send-back`, values).then(refresh)}
        />
      )}
      {dialog === "disposition" && (
        <RecordDialog
          title="Record disposition"
          fields={[
            { name: "disposition", label: "Disposition", type: "select", required: true, options: dispositions },
            { ...NOTE_FIELD, label: "Justification" },
          ]}
          onClose={() => setDialog(null)}
          onSubmit={(values) => api.post(`${path}/disposition`, values).then(refresh)}
        />
      )}
    </>
  );
}

function LinkedNcr({ inspectionId }: { inspectionId: string }) {
  const allowed = usePermission("NCR_VIEW");
  const query = `/ncrs?inspectionId=${inspectionId}`;
  const { data } = useQuery({
    queryKey: [query],
    queryFn: () => api.get<PageResponse<NcrSummary>>(query),
    enabled: allowed,
  });
  const ncr = data?.items[0];
  if (!ncr) return null;
  return (
    <p className="text-sm">
      Raised <RecordLink href={`/ncrs/${ncr.id}`}>{ncr.ncrNumber}</RecordLink> ({ncr.status.replaceAll("_", " ").toLowerCase()})
    </p>
  );
}

export default function InspectionPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/inspections/${id}`;
  const canExecute = usePermission("INSPECTION_EXECUTE");
  const canCreate = usePermission("INSPECTION_CREATE");
  const { run, message } = useAction();
  const { data: inspection, error } = useQuery({ queryKey: [path], queryFn: () => api.get<Inspection>(path) });
  if (!inspection) return <QueryState error={error} />;

  const { status } = inspection;
  const open = status === "PENDING" || status === "IN_PROGRESS";

  return (
    <div className="space-y-6">
      <PageHeader
        title={inspection.inspectionNumber}
        subtitle={
          <span className="flex flex-wrap gap-2">
            <StatusBadge status={status} />
            {inspection.evaluatedResult && <StatusBadge status={inspection.evaluatedResult} />}
            {inspection.disposition && <StatusBadge status={inspection.disposition} />}
          </span>
        }
      >
        {status === "PENDING" && canCreate && (
          <Button variant="outline" onClick={() => run(() => api.post(`${path}/cancel`))}>
            Cancel
          </Button>
        )}
        <Button variant="outline" render={<Link href={`/inspections/${id}/report`} />}>
          Report
        </Button>
      </PageHeader>
      <FormError message={message} />
      <Section title="Batch">
        <Details
          items={[
            ["Material", `${inspection.materialCode} – ${inspection.materialName}`],
            ["Lot", inspection.batchNumber],
            ["Supplier", inspection.supplierName],
            ["Quantity", formatQuantity(inspection.quantity)],
            ["Samples", inspection.sampleSize],
            ["Template", `${inspection.specification.templateCode} v${inspection.specification.versionNumber}`],
          ]}
        />
      </Section>
      <LinkedNcr inspectionId={id} />
      <Review inspection={inspection} />
      <Execution
        key={`${status}-${inspection.results.length}`}
        inspection={inspection}
        editable={open && canExecute}
      />
      <AttachmentPanel entityType="INSPECTION" entityId={id} canManage={open && canExecute} />
      {inspection.decisions.length > 0 && (
        <Section title="Decision history">
          <ul className="divide-y text-sm">
            {inspection.decisions.map((decision) => (
              <li key={decision.decidedAt + decision.decision} className="flex justify-between gap-3 py-2">
                <span>
                  {decision.decision.replaceAll("_", " ").toLowerCase()}
                  {decision.note && ` – ${decision.note}`}
                </span>
                <span className="shrink-0 text-muted-foreground">{new Date(decision.decidedAt).toLocaleString()}</span>
              </li>
            ))}
          </ul>
        </Section>
      )}
    </div>
  );
}
