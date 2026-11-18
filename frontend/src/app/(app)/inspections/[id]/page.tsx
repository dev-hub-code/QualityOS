"use client";

import { levelLabel } from "@/components/records/aql-switching";
import { useNcrStatusLabel } from "@/hooks/use-ncr-status-labels";
import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useParams } from "next/navigation";
import { InspectionInstruments } from "@/components/calibration/inspection-instruments";
import { useEffect, useState, type ReactNode } from "react";
import { FormError } from "@/components/forms/field";
import { AttachmentPanel } from "@/components/records/attachment-panel";
import {
  Details,
  PageHeader,
  QueryState,
  RecordLink,
  Section,
  StatusBadge,
  useAction,
  useRefresh,
  useUnits,
} from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { SelfReviewNotice } from "@/components/app/review-policy";
import { useMe, usePermission } from "@/hooks/use-me";
import { useSelfReviewInEffect } from "@/hooks/use-organization-settings";
import { api, type PageResponse } from "@/lib/api";
import {
  requirement,
  sampleKey,
  samplesOf,
  toEntries,
  toFormValues,
  toMeasuredWith,
} from "@/lib/inspection";
import {
  CHANGED_EVENT,
  isConnectionLost,
  queueResults,
  queuedFor,
  type QueuedEntry,
} from "@/lib/offline-results";
import { formatQuantity, type FieldDef } from "@/lib/record-form";
import type {
  Attachment,
  Inspection,
  NcrSummary,
  ParameterSpec,
} from "@/lib/types";

/** The lot's plan in words: one sample, or under double sampling the first and then both together. */
function planText(specification: Inspection["specification"]): string {
  const first = `accept up to ${specification.acceptanceNumber} nonconforming, reject from ${specification.rejectionNumber}`;
  const second = specification.secondSample;
  if (!second) return first;
  return `double sampling. First sample: ${first}; otherwise a second sample of ${second.sampleSize}, and both together accept up to ${second.acceptanceNumber}, reject from ${second.rejectionNumber}`;
}

const NOTE_FIELD: FieldDef = { name: "note", label: "Note" };
const SEND_BACK_FIELDS: FieldDef[] = [
  { ...NOTE_FIELD, label: "What needs correcting", required: true },
];
const SELECT_CLASS =
  "h-11 w-full rounded-xl border border-input bg-white/70 px-2 text-base";

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
      <select
        id={id}
        className={SELECT_CLASS}
        value={value}
        disabled={disabled}
        onChange={(e) => onChange(e.target.value)}
      >
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

function Evidence({
  inspectionId,
  parameter,
  attachments,
}: {
  inspectionId: string;
  parameter: ParameterSpec;
  attachments: Attachment[];
}) {
  const { run, pending, message } = useAction();
  const count = attachments.filter(
    (attachment) => attachment.category === parameter.code,
  ).length;
  return (
    <div className="mt-3 space-y-1.5">
      <FormError message={message} />
      <Label htmlFor={`evidence-${parameter.code}`}>
        Evidence for {parameter.code} ({count} added)
      </Label>
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
            api.upload(
              `/attachments?entityType=INSPECTION&entityId=${inspectionId}&category=${encodeURIComponent(parameter.code)}`,
              form,
            ),
          );
        }}
      />
    </div>
  );
}

function Execution({
  inspection,
  editable,
}: {
  inspection: Inspection;
  editable: boolean;
}) {
  const units = useUnits();
  const { data: me } = useMe();
  const path = `/inspections/${inspection.id}`;
  const attachmentsPath = `/attachments?entityType=INSPECTION&entityId=${inspection.id}`;
  // Results kept on this device by this person are shown over the saved ones until they are sent.
  const [kept, setKept] = useState(() => keptOnDevice(inspection.id, me));
  const [values, setValues] = useState(() => ({
    ...toFormValues(inspection.results),
    ...toFormValues(kept.map(asResult)),
  }));
  const [measuredWith, setMeasuredWith] = useState(() =>
    toMeasuredWith(inspection.results),
  );
  // When each value was typed, so one sent after a lost connection keeps its time.
  const [typedAt, setTypedAt] = useState<Record<string, string>>(() =>
    Object.fromEntries(
      kept.map((entry) => [
        sampleKey(entry.parameterCode, entry.sampleNumber),
        entry.measuredAt,
      ]),
    ),
  );
  const [offlineNote, setOfflineNote] = useState("");
  // Kept results sent in the background stop showing as kept.
  useEffect(() => {
    const refresh = () => setKept(keptOnDevice(inspection.id, me));
    window.addEventListener(CHANGED_EVENT, refresh);
    return () => window.removeEventListener(CHANGED_EVENT, refresh);
  }, [inspection.id, me]);
  const [formError, setFormError] = useState("");
  const { run, pending, message } = useAction();
  const { data: attachments = [] } = useQuery({
    queryKey: [attachmentsPath],
    queryFn: () => api.get<Attachment[]>(attachmentsPath),
  });
  const { parameters } = inspection.specification;
  const keptKeys = new Set(
    kept.map((entry) => sampleKey(entry.parameterCode, entry.sampleNumber)),
  );

  /** Saving first means that nothing typed can be lost on submission. */
  function save(thenSubmit: boolean) {
    const { entries, invalid } = toEntries(
      parameters,
      inspection.sampleSize,
      values,
      measuredWith,
    );
    if (invalid.length > 0) {
      setFormError(`Check these values: ${invalid.join(", ")}.`);
      return;
    }
    setFormError("");
    setOfflineNote("");
    const now = new Date().toISOString();
    const stamped: QueuedEntry[] = entries.map((entry) => {
      const key = sampleKey(entry.parameterCode, entry.sampleNumber);
      const saved = inspection.results.find(
        (result) =>
          sampleKey(result.parameterCode, result.sampleNumber) === key,
      );
      return { ...entry, measuredAt: typedAt[key] ?? saved?.measuredAt ?? now };
    });
    /** Keeps the values on this device until the connection is back. */
    function keep() {
      if (!me) return false;
      queueResults(
        {
          inspectionId: inspection.id,
          inspectionNumber: inspection.inspectionNumber,
          userId: me.user.id,
          tenantId: me.tenantId,
        },
        stamped,
      );
      setKept(keptOnDevice(inspection.id, me));
      setOfflineNote(
        thenSubmit
          ? "No connection. The values are kept on this device and sent when it is back. Submit then."
          : "No connection. The values are kept on this device and sent when it is back.",
      );
      return true;
    }
    // Offline, requests wait for the connection rather than fail, so keep the values at once.
    if (navigator.onLine === false && stamped.length > 0 && keep()) return;
    run(async () => {
      try {
        if (stamped.length > 0)
          await api.post(`${path}/execute`, { results: stamped });
      } catch (error) {
        if (isConnectionLost(error) && keep()) return;
        throw error;
      }
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
          action={
            parameter.criticality === "CRITICAL" ? (
              <StatusBadge status="HOLD" />
            ) : undefined
          }
        >
          <p className="mb-3 text-sm text-muted-foreground">
            {parameter.code} · {requirement(parameter)}{" "}
            {units.label(parameter.uomId)}
            {parameter.inspectionMethod && ` · ${parameter.inspectionMethod}`}
            {parameter.criticality === "CRITICAL" && " · critical"}
          </p>
          {inspection.instruments.length > 0 && (
            <div className="mb-3 flex flex-wrap items-center gap-2 text-sm">
              <Label htmlFor={`instrument-${parameter.code}`}>
                {parameter.code} measured with
              </Label>
              <select
                id={`instrument-${parameter.code}`}
                className="h-9 rounded-xl border bg-white/70 px-2 text-sm"
                disabled={!editable}
                value={measuredWith[parameter.code] ?? ""}
                onChange={(event) =>
                  setMeasuredWith((current) => ({
                    ...current,
                    [parameter.code]: event.target.value,
                  }))
                }
              >
                <option value="">Not said</option>
                {inspection.instruments.map((instrument) => (
                  <option key={instrument.id} value={instrument.id}>
                    {instrument.instrumentCode} – {instrument.name}
                  </option>
                ))}
              </select>
            </div>
          )}
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            {Array.from(
              { length: samplesOf(parameter, inspection.sampleSize) },
              (_, index) => index + 1,
            ).map((sample) => {
              const key = sampleKey(parameter.code, sample);
              const saved = inspection.results.find(
                (result) =>
                  result.parameterCode === parameter.code &&
                  result.sampleNumber === sample,
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
                    onChange={(value) => {
                      setValues((current) => ({ ...current, [key]: value }));
                      setTypedAt((current) => ({
                        ...current,
                        [key]: new Date().toISOString(),
                      }));
                    }}
                  />
                  {keptKeys.has(key) ? (
                    <p className="text-xs text-amber-700">
                      On this device, not sent yet
                    </p>
                  ) : (
                    saved && <StatusBadge status={saved.evaluation} />
                  )}
                </div>
              );
            })}
          </div>
          {editable && parameter.evidenceRequired && (
            <Evidence
              inspectionId={inspection.id}
              parameter={parameter}
              attachments={attachments}
            />
          )}
        </Section>
      ))}
      {offlineNote && (
        <p role="status" className="text-sm text-amber-700">
          {offlineNote}
        </p>
      )}
      <FormError message={formError || message} />
      {editable && (
        <div className="sticky bottom-0 flex gap-3 border-t bg-background py-3">
          <Button
            variant="outline"
            className="h-11 flex-1 sm:flex-none"
            disabled={pending}
            onClick={() => save(false)}
          >
            Save
          </Button>
          <Button
            className="h-11 flex-1 sm:flex-none"
            disabled={pending}
            onClick={() => save(true)}
          >
            Submit for approval
          </Button>
        </div>
      )}
    </div>
  );
}

/** What this person kept on this device for the inspection, waiting to be sent. */
function keptOnDevice(
  inspectionId: string,
  me: { user: { id: string }; tenantId: string } | undefined,
): QueuedEntry[] {
  const item =
    typeof window === "undefined" ? undefined : queuedFor(inspectionId);
  return item &&
    me &&
    item.userId === me.user.id &&
    item.tenantId === me.tenantId
    ? item.entries
    : [];
}

/** A kept entry in the shape of a saved result, to fill the form with. */
function asResult(entry: QueuedEntry): Inspection["results"][number] {
  return {
    parameterCode: entry.parameterCode,
    sampleNumber: entry.sampleNumber,
    valueNumeric: entry.valueNumeric ?? null,
    valueText: entry.valueText ?? null,
    valueBoolean: entry.valueBoolean ?? null,
    valueDate: entry.valueDate ?? null,
    evaluation: "PENDING_REVIEW",
    remarks: null,
    instrumentId: entry.instrumentId ?? null,
    measuredAt: entry.measuredAt,
  };
}

type Dialog = "approve" | "send-back" | "disposition" | null;

function Review({ inspection }: { inspection: Inspection }) {
  const path = `/inspections/${inspection.id}`;
  const { data: me } = useMe();
  const canApprove = usePermission("INSPECTION_APPROVE");
  const canDispose = usePermission("DISPOSITION_RECORD");
  const refresh = useRefresh();
  const selfReviewInEffect = useSelfReviewInEffect();
  const [dialog, setDialog] = useState<Dialog>(null);
  const { status, finalResult, evaluatedResult } = inspection;
  const inspector = inspection.decisions.findLast(
    (decision) => decision.decision === "SUBMITTED",
  )?.decidedBy;
  const independent = me?.user.id !== inspector;
  const selfReview = !independent && selfReviewInEffect;
  const mayReview = independent || selfReview;

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
    selfReview ? { ...NOTE_FIELD, required: true } : NOTE_FIELD,
  ];

  return (
    <>
      {status === "SUBMITTED" && canApprove && !mayReview && (
        <p className="text-sm text-muted-foreground">
          Someone other than the inspector must review this inspection.
        </p>
      )}
      {status === "SUBMITTED" && canApprove && selfReview && (
        <SelfReviewNotice />
      )}
      {status === "SUBMITTED" && canApprove && mayReview && (
        <div className="flex flex-wrap gap-3">
          <Button onClick={() => setDialog("approve")}>Approve</Button>
          <Button variant="outline" onClick={() => setDialog("send-back")}>
            Send back
          </Button>
        </div>
      )}
      {status === "APPROVED" && canDispose && (
        <Button onClick={() => setDialog("disposition")}>
          Record disposition
        </Button>
      )}
      {dialog === "approve" && (
        <RecordDialog
          title="Approve inspection"
          fields={approveFields}
          onClose={() => setDialog(null)}
          onSubmit={(values) =>
            api.post(`${path}/approve`, values).then(refresh)
          }
        />
      )}
      {dialog === "send-back" && (
        <RecordDialog
          title="Send back to inspector"
          fields={SEND_BACK_FIELDS}
          onClose={() => setDialog(null)}
          onSubmit={(values) =>
            api.post(`${path}/send-back`, values).then(refresh)
          }
        />
      )}
      {dialog === "disposition" && (
        <RecordDialog
          title="Record disposition"
          fields={[
            {
              name: "disposition",
              label: "Disposition",
              type: "select",
              required: true,
              options: dispositions,
            },
            { ...NOTE_FIELD, label: "Justification" },
          ]}
          onClose={() => setDialog(null)}
          onSubmit={(values) =>
            api.post(`${path}/disposition`, values).then(refresh)
          }
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
  const ncrStatus = useNcrStatusLabel();
  const ncr = data?.items[0];
  if (!ncr) return null;
  return (
    <p className="text-sm">
      Raised <RecordLink href={`/ncrs/${ncr.id}`}>{ncr.ncrNumber}</RecordLink> (
      {ncrStatus(ncr.status)})
    </p>
  );
}

export default function InspectionPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/inspections/${id}`;
  const canExecute = usePermission("INSPECTION_EXECUTE");
  const canCreate = usePermission("INSPECTION_CREATE");
  const { run, message } = useAction();
  const { data: inspection, error } = useQuery({
    queryKey: [path],
    queryFn: () => api.get<Inspection>(path),
  });
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
            {inspection.evaluatedResult && (
              <StatusBadge status={inspection.evaluatedResult} />
            )}
            {inspection.disposition && (
              <StatusBadge status={inspection.disposition} />
            )}
          </span>
        }
      >
        {status === "PENDING" && canCreate && (
          <Button
            variant="outline"
            onClick={() => run(() => api.post(`${path}/cancel`))}
          >
            Cancel
          </Button>
        )}
        <Button
          variant="outline"
          render={<Link href={`/inspections/${id}/report`} />}
        >
          Report
        </Button>
      </PageHeader>
      <FormError message={message} />
      <Section title="Batch">
        <Details
          items={[
            [
              "Material",
              `${inspection.materialCode} – ${inspection.materialName}`,
            ],
            ["Lot", inspection.batchNumber],
            ["Supplier", inspection.supplierName],
            ["Quantity", formatQuantity(inspection.quantity)],
            ["Samples", inspection.sampleSize],
            [
              "Template",
              `${inspection.specification.templateCode} v${inspection.specification.versionNumber}`,
            ],
            ...(inspection.specification.severity
              ? ([
                  [
                    "Sampling plan",
                    `AQL ${Number(inspection.specification.aql)}, level ${levelLabel(inspection.specification.inspectionLevel)}, ${inspection.specification.severity.toLowerCase()} inspection, letter ${inspection.specification.codeLetter}: ${planText(inspection.specification)}`,
                  ],
                ] as [string, string][])
              : []),
            ...(inspection.reinspectionOf
              ? ([
                  [
                    "Checks again",
                    <RecordLink
                      key="of"
                      href={`/inspections/${inspection.reinspectionOf.id}`}
                    >
                      {inspection.reinspectionOf.inspectionNumber}, put in doubt
                      by a failed calibration
                    </RecordLink>,
                  ],
                ] as [string, ReactNode][])
              : []),
            ...(inspection.reinspectedBy
              ? ([
                  [
                    "Checked again by",
                    <RecordLink
                      key="by"
                      href={`/inspections/${inspection.reinspectedBy.id}`}
                    >
                      {inspection.reinspectedBy.inspectionNumber}
                    </RecordLink>,
                  ],
                ] as [string, ReactNode][])
              : []),
          ]}
        />
      </Section>
      {inspection.secondSampleDrawn && open && (
        <p className="rounded-md border bg-muted/40 p-3 text-sm" role="status">
          The first sample was inconclusive, so a second sample of{" "}
          {inspection.specification.secondSample?.sampleSize} is needed. Record
          samples{" "}
          {inspection.sampleSize -
            (inspection.specification.secondSample?.sampleSize ?? 0) +
            1}{" "}
          to {inspection.sampleSize}, then submit again; both samples are
          counted together.
        </p>
      )}
      <LinkedNcr inspectionId={id} />
      <Review inspection={inspection} />
      <Execution
        key={`${status}-${inspection.results.length}-${inspection.sampleSize}`}
        inspection={inspection}
        editable={open && canExecute}
      />
      <AttachmentPanel
        entityType="INSPECTION"
        entityId={id}
        canManage={open && canExecute}
      />
      {inspection.decisions.length > 0 && (
        <Section title="Decision history">
          <ul className="divide-y text-sm">
            {inspection.decisions.map((decision) => (
              <li
                key={decision.decidedAt + decision.decision}
                className="flex justify-between gap-3 py-2"
              >
                <span>
                  {decision.decision.replaceAll("_", " ").toLowerCase()}
                  {decision.note && ` – ${decision.note}`}
                </span>
                <span className="shrink-0 text-muted-foreground">
                  {new Date(decision.decidedAt).toLocaleString()}
                </span>
              </li>
            ))}
          </ul>
        </Section>
      )}
    </div>
  );
}
