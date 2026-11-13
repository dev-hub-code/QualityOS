"use client";

import { useQuery } from "@tanstack/react-query";
import { useParams } from "next/navigation";
import { useState } from "react";
import { FormError } from "@/components/forms/field";
import { AttachmentPanel } from "@/components/records/attachment-panel";
import { Details, PageHeader, QueryState, RecordLink, Section, StatusBadge, useAction, useRefresh, useUnits } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { useMe } from "@/hooks/use-me";
import { api } from "@/lib/api";
import {
  LOSS_CATEGORY_LABELS,
  describeLimits,
  formatDuration,
  newRequestKey,
  operationActions,
  reportMismatch,
} from "@/lib/manufacturing";
import { formatQuantity, type FieldDef } from "@/lib/record-form";
import type { LossCategory, OperationDetail } from "@/lib/types";

type Dialog = "start" | "pause" | "report" | "measure" | "complete" | "assign" | "block" | "skip" | { reverse: string } | null;

const CATEGORIES = (Object.keys(LOSS_CATEGORY_LABELS) as LossCategory[])
  .filter((category) => category !== "ORDER_ON_HOLD")
  .map((value) => ({ value, label: LOSS_CATEGORY_LABELS[value] }));

/** One operation, run from a phone or a tablet at the line. Every change is checked again by the server. */
export default function OperationPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/manufacturing/operations/${id}`;
  const me = useMe().data;
  const units = useUnits();
  const refresh = useRefresh();
  const { run, pending, message } = useAction();
  const [dialog, setDialog] = useState<Dialog>(null);
  const [key, setKey] = useState(newRequestKey);
  const [comment, setComment] = useState("");
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<OperationDetail>(path), refetchInterval: 30_000 });
  if (!data) return <QueryState error={error} />;

  const { operation } = data;
  const can = {
    id: me?.user.id,
    execute: me?.permissions.includes("PRODUCTION_EXECUTE") ?? false,
    supervise: me?.permissions.includes("PRODUCTION_SUPERVISE") ?? false,
  };
  const actions = operationActions(operation, can);
  const unit = units.label(operation.uomId);
  const amount = (value: string) => `${formatQuantity(value)} ${unit}`;
  const open = (next: Dialog) => {
    setKey(newRequestKey());
    setDialog(next);
  };
  const close = () => setDialog(null);
  const post = (suffix: string, body: unknown = {}, headers?: Record<string, string>) =>
    api.post(`${path}${suffix}`, body, headers).then(refresh);
  const freeMachines = data.machines.filter((machine) => machine.status !== "INACTIVE");
  const startNow = () => (operation.machineId || freeMachines.length === 0 ? run(() => post("/start")) : open("start"));
  const big = "h-12 px-5 text-base";

  return (
    <div className="space-y-6">
      <PageHeader
        title={`${operation.sequence}. ${operation.name}`}
        subtitle={
          <span className="flex flex-wrap items-center gap-2">
            <StatusBadge status={operation.status} />
            <RecordLink href={`/manufacturing/work-orders/${operation.workOrderId}`}>{operation.workOrderNumber}</RecordLink>
            {operation.productCode} – {operation.productName} · {operation.workCenterCode}
          </span>
        }
      />
      <FormError message={message} />
      {operation.workOrderStatus === "ON_HOLD" && (
        <p className="rounded-xl bg-amber-50 px-3 py-2 text-sm text-amber-900 ring-1 ring-amber-200">
          The work order is on hold. Nothing runs until it is resumed.
        </p>
      )}
      {operation.statusReason && (
        <p className="rounded-xl bg-muted px-3 py-2 text-sm">{operation.statusReason}</p>
      )}

      <div className="grid grid-cols-2 gap-2 sm:flex sm:flex-wrap">
        {actions.includes("start") && (
          <Button className={big} disabled={pending} onClick={startNow}>
            Start
          </Button>
        )}
        {actions.includes("resume") && (
          <Button className={big} disabled={pending} onClick={() => run(() => post("/resume"))}>
            Resume
          </Button>
        )}
        {actions.includes("report") && (
          <Button className={big} onClick={() => open("report")}>
            Report quantities
          </Button>
        )}
        {actions.includes("pause") && (
          <Button className={big} variant="outline" onClick={() => open("pause")}>
            Pause
          </Button>
        )}
        {actions.includes("measure") && data.parameters.length > 0 && (
          <Button className={big} variant="outline" onClick={() => open("measure")}>
            Record measurement
          </Button>
        )}
        {actions.includes("complete") && (
          <Button className={big} variant="outline" disabled={data.previousOpen} onClick={() => open("complete")}>
            Complete
          </Button>
        )}
      </div>
      {actions.includes("complete") && data.previousOpen && (
        <p className="text-sm text-muted-foreground">It can be completed once every operation before it is complete.</p>
      )}

      <Section title="Quantities">
        <Details
          items={[
            ["Can come in", amount(operation.input)],
            ["Processed", amount(operation.processed)],
            ["Good", amount(operation.goodQuantity)],
            [
              "Rejected",
              Number(operation.reworkQuantity) > 0
                ? `${amount(operation.rejectedQuantity)}, ${formatQuantity(operation.reworkQuantity)} to rework`
                : amount(operation.rejectedQuantity),
            ],
            ["Scrap", amount(operation.scrapQuantity)],
            ["Waiting", amount(operation.waiting)],
          ]}
        />
      </Section>

      <Section
        title="Work"
        action={
          (actions.includes("assign") || actions.includes("block") || actions.includes("unblock") || actions.includes("skip")) && (
            <span className="flex flex-wrap gap-2">
              {actions.includes("assign") && (
                <Button size="sm" variant="outline" onClick={() => open("assign")}>
                  Assign
                </Button>
              )}
              {actions.includes("block") && (
                <Button size="sm" variant="outline" onClick={() => open("block")}>
                  Block
                </Button>
              )}
              {actions.includes("unblock") && (
                <Button size="sm" variant="outline" disabled={pending} onClick={() => run(() => post("/unblock"))}>
                  Unblock
                </Button>
              )}
              {actions.includes("skip") && (
                <Button size="sm" variant="ghost" onClick={() => open("skip")}>
                  Skip
                </Button>
              )}
            </span>
          )
        }
      >
        <Details
          items={[
            ["Operator", operation.operatorName ?? "Not assigned"],
            ["Machine", operation.machineCode ?? "None"],
            ["Work centre", `${operation.workCenterCode} – ${operation.workCenterName}`],
            ["Running time", formatDuration(data.runningSeconds)],
            ["Planned", `Setup ${formatQuantity(data.plannedSetupMinutes)} min · run ${formatQuantity(data.plannedRunMinutes)} min`],
            ["Setup took", data.actualSetupMinutes === null ? "" : `${formatQuantity(data.actualSetupMinutes)} min`],
            ["Started", operation.startedAt ? new Date(operation.startedAt).toLocaleString() : ""],
            ["Completed", operation.completedAt ? new Date(operation.completedAt).toLocaleString() : ""],
            ["Due", operation.plannedEnd],
          ]}
        />
        {data.instructions && <p className="mt-4 whitespace-pre-line rounded-xl bg-muted px-3 py-2 text-sm">{data.instructions}</p>}
      </Section>

      {data.parameters.length > 0 && (
        <Section title="Parameters">
          <ul className="divide-y text-sm">
            {data.parameters.map((parameter) => (
              <li key={parameter.code} className="flex flex-wrap justify-between gap-2 py-2">
                <span>
                  {parameter.code} – {parameter.name}
                  {parameter.source === "RECIPE" && <span className="ml-2 text-muted-foreground">recipe</span>}
                </span>
                <span className="text-muted-foreground">{describeLimits(parameter)}</span>
              </li>
            ))}
          </ul>
          {data.measurements.length > 0 && (
            <ul className="mt-4 divide-y border-t text-sm">
              {data.measurements.map((measurement) => (
                <li key={measurement.id} className="flex flex-wrap items-center justify-between gap-2 py-2">
                  <span className="flex items-center gap-2">
                    <StatusBadge status={measurement.withinLimits ? "PASS" : "FAIL"} />
                    {measurement.parameterCode} {formatQuantity(measurement.value)} {measurement.unit ?? ""}
                    {measurement.comment && <span className="text-muted-foreground">· {measurement.comment}</span>}
                  </span>
                  <span className="text-muted-foreground">
                    {measurement.measuredByName ?? "Former member"} · {new Date(measurement.measuredAt).toLocaleString()}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </Section>
      )}

      {data.reports.length > 0 && (
        <Section title="Reports">
          <ul className="divide-y text-sm">
            {data.reports.map((report) => (
              <li key={report.id} className="flex flex-wrap items-center justify-between gap-3 py-2">
                <span className={report.reversed ? "text-muted-foreground line-through" : undefined}>
                  {report.reversesReportId && "Reversal: "}
                  good {formatQuantity(report.goodQuantity)} · rejected {formatQuantity(report.rejectedQuantity)}
                  {Number(report.reworkQuantity) !== 0 && ` (${formatQuantity(report.reworkQuantity)} rework)`} · scrap{" "}
                  {formatQuantity(report.scrapQuantity)}
                  {report.comment && <span className="text-muted-foreground"> · {report.comment}</span>}
                </span>
                <span className="flex items-center gap-2 text-muted-foreground">
                  {report.reportedByName ?? "Former member"} · {new Date(report.reportedAt).toLocaleString()}
                  {can.supervise && !report.reversed && !report.reversesReportId &&
                    operation.status !== "COMPLETED" && operation.status !== "CANCELLED" && (
                      <Button size="sm" variant="ghost" onClick={() => open({ reverse: report.id })}>
                        Reverse
                      </Button>
                    )}
                </span>
              </li>
            ))}
          </ul>
        </Section>
      )}

      <Section title="Timeline">
        <ul className="divide-y text-sm">
          {data.events.map((event) => (
            <li key={event.id} className="flex flex-wrap items-center justify-between gap-3 py-2">
              <span className="flex flex-wrap items-center gap-2">
                <StatusBadge status={event.eventType} />
                {event.lossCategory && <span>{LOSS_CATEGORY_LABELS[event.lossCategory]}</span>}
                {event.eventType === "ASSIGNED" && (
                  <span>
                    {event.operatorName ?? "nobody"}
                    {event.machineCode && ` on ${event.machineCode}`}
                  </span>
                )}
                {event.comment && <span className="text-muted-foreground">{event.comment}</span>}
              </span>
              <span className="text-muted-foreground">
                {event.byName ?? "Former member"} · {new Date(event.at).toLocaleString()}
              </span>
            </li>
          ))}
        </ul>
        {can.execute && operation.workOrderStatus !== "COMPLETED" && operation.workOrderStatus !== "CANCELLED" && (
          <form
            className="mt-3 flex gap-2"
            onSubmit={(event) => {
              event.preventDefault();
              if (comment.trim()) run(() => post("/comments", { comment }).then(() => setComment("")));
            }}
          >
            <Input aria-label="Comment" placeholder="Add a comment" value={comment} onChange={(event) => setComment(event.target.value)} />
            <Button type="submit" variant="outline" disabled={pending || !comment.trim()}>
              Add
            </Button>
          </form>
        )}
      </Section>

      <AttachmentPanel entityType="WORK_ORDER_OPERATION" entityId={id} canManage={can.execute} />

      {dialog === "start" && (
        <RecordDialog
          title="Start the operation"
          fields={[
            {
              name: "machineId",
              label: "Machine",
              type: "select",
              options: freeMachines.map((machine) => ({
                value: machine.id,
                label: `${machine.code} – ${machine.name}${machine.busy ? " (running another operation)" : ""}${
                  machine.status !== "AVAILABLE" && machine.status !== "RUNNING" ? ` (${machine.status.toLowerCase()})` : ""
                }`,
              })),
            },
          ]}
          onClose={close}
          onSubmit={(values) => post("/start", values)}
        />
      )}
      {(dialog === "pause" || dialog === "block") && (
        <RecordDialog
          title={dialog === "pause" ? "Pause the operation" : "Block the operation"}
          fields={[
            { name: "category", label: "Why it stopped", type: "select", required: true, options: CATEGORIES },
            { name: "reason", label: "Details", type: "textarea", required: dialog === "block" },
          ]}
          onClose={close}
          onSubmit={(values) => post(`/${dialog}`, values)}
        />
      )}
      {dialog === "report" && (
        <RecordDialog
          title="Report quantities"
          fields={reportFields(unit)}
          record={{ rejectedQuantity: "0", scrapQuantity: "0" }}
          onClose={close}
          onSubmit={(values) => {
            const mismatch = reportMismatch(values as Record<"quantity" | "goodQuantity" | "rejectedQuantity" | "scrapQuantity", string>);
            if (mismatch) return Promise.reject(new Error(mismatch));
            return post("/reports", values, { "Idempotency-Key": key });
          }}
        />
      )}
      {dialog === "measure" && (
        <RecordDialog
          title="Record a measurement"
          fields={[
            {
              name: "parameterCode",
              label: "Parameter",
              type: "select",
              required: true,
              options: data.parameters.map((parameter) => ({ value: parameter.code, label: `${parameter.code} – ${parameter.name}` })),
            },
            { name: "value", label: "Value", type: "decimal", required: true },
            { name: "comment", label: "Comment", type: "textarea" },
          ]}
          onClose={close}
          onSubmit={(values) => post("/measurements", values, { "Idempotency-Key": key })}
        />
      )}
      {dialog === "complete" && (
        <RecordDialog
          title="Complete the operation"
          fields={[
            { name: "setupMinutes", label: "Setup took, in minutes", type: "decimal" },
            {
              name: "reason",
              label:
                Number(operation.processed) < Number(operation.input)
                  ? `Only ${amount(operation.processed)} of ${amount(operation.input)} went through. Why is it complete?`
                  : "Note",
              type: "textarea",
              required: Number(operation.processed) < Number(operation.input),
            },
          ]}
          onClose={close}
          onSubmit={(values) => post("/complete", values)}
        />
      )}
      {dialog === "assign" && (
        <RecordDialog
          title="Assign the operation"
          fields={[
            {
              name: "operatorId",
              label: "Operator",
              type: "select",
              options: data.operators.map((person) => ({ value: person.id, label: person.name })),
            },
            {
              name: "machineId",
              label: "Machine",
              type: "select",
              options: freeMachines.map((machine) => ({ value: machine.id, label: `${machine.code} – ${machine.name}` })),
            },
          ]}
          record={{ operatorId: operation.operatorId ?? "", machineId: operation.machineId ?? "" }}
          onClose={close}
          onSubmit={(values) => post("/assign", values)}
        />
      )}
      {dialog === "skip" && (
        <RecordDialog
          title="Skip the operation"
          fields={[{ name: "reason", label: "Why it is not needed", type: "textarea", required: true }]}
          onClose={close}
          onSubmit={(values) => post("/skip", values)}
        />
      )}
      {dialog !== null && typeof dialog === "object" && (
        <RecordDialog
          title="Reverse the report"
          fields={[{ name: "reason", label: "Why it was wrong", type: "textarea", required: true }]}
          onClose={close}
          onSubmit={(values) => post(`/reports/${dialog.reverse}/reverse`, values)}
        />
      )}
    </div>
  );
}

function reportFields(unit: string): FieldDef[] {
  return [
    { name: "quantity", label: `Processed, in ${unit}`, type: "decimal", required: true },
    { name: "goodQuantity", label: "Good", type: "decimal", required: true },
    { name: "rejectedQuantity", label: "Rejected", type: "decimal", required: true },
    { name: "reworkQuantity", label: "Of the rejected, to rework", type: "decimal" },
    { name: "scrapQuantity", label: "Scrap", type: "decimal", required: true },
    { name: "comment", label: "Comment", type: "textarea" },
  ];
}
