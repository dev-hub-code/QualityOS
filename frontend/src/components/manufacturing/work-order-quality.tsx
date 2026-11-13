"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { FormError } from "@/components/forms/field";
import { RecordLink, Section, StatusBadge, useAction, useRefresh, useUnits, useWorkCenters } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { STAGE_LABELS, gateInspectable, parseSteps } from "@/lib/manufacturing";
import { formatQuantity } from "@/lib/record-form";
import type { QualityGate, WorkOrderQuality, WorkOrderStatus } from "@/lib/types";

type Dialog = "hold" | "rework" | null;

/** The latest inspection of a gate, linked to the inspection engine where it is carried out. */
function LatestInspection({ gate }: { gate: QualityGate }) {
  const latest = gate.inspections.at(-1);
  if (!latest) return <span className="text-muted-foreground">Not inspected</span>;
  return (
    <span>
      <RecordLink href={`/inspections/${latest.id}`}>{latest.inspectionNumber}</RecordLink>
      {latest.disposition && <span className="text-muted-foreground"> · {latest.disposition.replaceAll("_", " ").toLowerCase()}</span>}
    </span>
  );
}

/**
 * Quality on a work order: its gates, a quality hold, NCRs and CAPAs on its batch, rework orders, and what completion
 * still waits for. Inspections themselves are carried out in the inspection pages.
 */
export function WorkOrderQualityPanel({
  orderId,
  status,
  operations,
}: {
  orderId: string;
  status: WorkOrderStatus;
  operations: { id: string; sequence: number; name: string }[];
}) {
  const path = `/manufacturing/work-orders/${orderId}`;
  const can = {
    inspect: usePermission("INSPECTION_CREATE"),
    hold: usePermission("INSPECTION_APPROVE"),
    rework: usePermission("REWORK_MANAGE"),
  };
  const units = useUnits();
  const workCenters = useWorkCenters();
  const refresh = useRefresh();
  const { run, pending, message } = useAction();
  const [dialog, setDialog] = useState<Dialog>(null);
  const { data } = useQuery({
    queryKey: [`${path}/quality`],
    queryFn: () => api.get<WorkOrderQuality>(`${path}/quality`),
  });
  if (!data) return null;

  const onFloor = status === "IN_PROGRESS" || status === "ON_HOLD";
  const close = () => setDialog(null);
  const reworkInspections = data.gates
    .flatMap((gate) => gate.inspections)
    .filter((inspection) => inspection.disposition === "REWORK");
  const nothingYet = data.gates.length === 0 && data.reworkOrders.length === 0 && data.ncrs.length === 0 && !data.batchId;
  if (nothingYet) return null;

  return (
    <>
      <Section
        title="Quality"
        action={
          <span className="flex flex-wrap gap-2">
            {can.rework && onFloor && data.batchStatus !== "REJECTED" && (
              <Button size="sm" variant="outline" onClick={() => setDialog("rework")}>
                Raise rework order
              </Button>
            )}
            {can.hold && onFloor && data.batchStatus === "IN_PROGRESS" && (
              <Button size="sm" variant="destructive" onClick={() => setDialog("hold")}>
                Quality hold
              </Button>
            )}
          </span>
        }
      >
        <FormError message={message} />
        {data.onHold && (
          <p className="mb-3 rounded-xl bg-red-50 px-3 py-2 text-sm text-red-900 ring-1 ring-red-200">
            Batch {data.batchNumber} is on hold for quality. Nothing more is made or reported on it until an in-process reinspection
            accepts it.
          </p>
        )}
        {data.unresolved.length > 0 && (
          <div className="mb-3 rounded-xl bg-amber-50 px-3 py-2 text-sm text-amber-900 ring-1 ring-amber-200">
            <p className="font-medium">Completion waits for</p>
            <ul className="mt-1 list-disc space-y-0.5 pl-5">
              {data.unresolved.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          </div>
        )}

        <h3 className="mb-1 text-sm font-medium">Quality gates</h3>
        {data.gates.length === 0 ? (
          <p className="text-sm text-muted-foreground">No quality checkpoints applied when this order was released.</p>
        ) : (
          <ul className="divide-y text-sm">
            {data.gates.map((gate) => (
              <li key={gate.id} className="flex flex-wrap items-center justify-between gap-3 py-2">
                <span>
                  <span className="font-medium">{gate.name}</span>
                  {!gate.mandatory && <span className="text-muted-foreground"> (optional)</span>}
                  <span className="block text-muted-foreground">
                    {gate.operationSequence !== null ? `Operation ${gate.operationSequence} ${gate.operationName}` : "Whole batch"} ·{" "}
                    {STAGE_LABELS[gate.stage]} · {gate.templateCode}
                  </span>
                </span>
                <span className="flex flex-wrap items-center gap-2">
                  <LatestInspection gate={gate} />
                  <StatusBadge status={gate.status} />
                  {gateInspectable(gate, status, can.inspect) && (
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={pending}
                      onClick={() => run(() => api.post(`${path}/quality-gates/${gate.id}/inspections`))}
                    >
                      {gate.status === "FAILED" ? "Inspect again" : "Inspect"}
                    </Button>
                  )}
                </span>
              </li>
            ))}
          </ul>
        )}

        {data.batchId && (
          <>
            <h3 className="mt-4 mb-1 text-sm font-medium">Rejected production</h3>
            <p className="text-sm text-muted-foreground">
              Rejected {formatQuantity(data.rejected.rejected)} · marked for rework {formatQuantity(data.rejected.toRework)} · in rework{" "}
              {formatQuantity(data.rejected.inRework)} · reworked and accepted {formatQuantity(data.rejected.reworkedAccepted)} · scrapped{" "}
              {formatQuantity(data.rejected.scrapped)}
            </p>
          </>
        )}

        {data.reworkOrders.length > 0 && (
          <>
            <h3 className="mt-4 mb-1 text-sm font-medium">Rework orders</h3>
            <ul className="divide-y text-sm">
              {data.reworkOrders.map((rework) => (
                <li key={rework.id} className="flex flex-wrap items-center justify-between gap-3 py-2">
                  <span>
                    <RecordLink href={`/manufacturing/rework-orders/${rework.id}`}>{rework.reworkNumber}</RecordLink>{" "}
                    {formatQuantity(rework.quantity)} {units.label(rework.uomId)}
                    {rework.operationSequence !== null && <span className="text-muted-foreground"> from operation {rework.operationSequence}</span>}
                  </span>
                  <span className="flex items-center gap-2">
                    {rework.finalDisposition && <span className="text-muted-foreground">{rework.finalDisposition.replaceAll("_", " ").toLowerCase()}</span>}
                    <StatusBadge status={rework.status} />
                  </span>
                </li>
              ))}
            </ul>
          </>
        )}

        {data.ncrs.length > 0 && (
          <>
            <h3 className="mt-4 mb-1 text-sm font-medium">NCRs and CAPAs</h3>
            <ul className="divide-y text-sm">
              {data.ncrs.map((ncr) => (
                <li key={ncr.id} className="py-2">
                  <span className="flex flex-wrap items-center justify-between gap-3">
                    <span>
                      <RecordLink href={`/ncrs/${ncr.id}`}>{ncr.ncrNumber}</RecordLink> {ncr.title}
                    </span>
                    <StatusBadge status={ncr.status} />
                  </span>
                  {data.capas
                    .filter((capa) => capa.ncrId === ncr.id)
                    .map((capa) => (
                      <span key={capa.id} className="mt-1 flex flex-wrap items-center justify-between gap-3 pl-4 text-muted-foreground">
                        <span>
                          CAPA <RecordLink href={`/capas/${capa.id}`}>{capa.capaNumber}</RecordLink> {capa.title}
                        </span>
                        <StatusBadge status={capa.status} />
                      </span>
                    ))}
                </li>
              ))}
            </ul>
          </>
        )}
      </Section>
      {dialog === "hold" && (
        <RecordDialog
          title="Put the batch on quality hold"
          fields={[{ name: "reason", label: "Why production must stop", type: "textarea", required: true }]}
          onClose={close}
          onSubmit={(values) => api.post(`${path}/quality-hold`, values).then(refresh)}
        />
      )}
      {dialog === "rework" && (
        <RecordDialog
          title="Raise a rework order"
          fields={[
            {
              name: "operationId",
              label: "Rejected goods from operation",
              type: "select",
              options: operations.map((operation) => ({ value: operation.id, label: `${operation.sequence}. ${operation.name}` })),
            },
            {
              name: "inspectionId",
              label: "Inspection disposed of as rework",
              type: "select",
              options: reworkInspections.map((inspection) => ({ value: inspection.id, label: inspection.inspectionNumber })),
            },
            {
              name: "ncrId",
              label: "NCR",
              type: "select",
              options: data.ncrs.map((ncr) => ({ value: ncr.id, label: `${ncr.ncrNumber} – ${ncr.title}` })),
            },
            { name: "quantity", label: "Quantity", type: "decimal", required: true },
            { name: "reason", label: "Reason", type: "textarea", required: true },
            { name: "instructions", label: "Instructions", type: "textarea" },
            { name: "steps", label: "Steps, one per line", type: "textarea", required: true },
            { name: "workCenterId", label: "Work centre for the steps", type: "select", options: workCenters },
          ]}
          onClose={close}
          onSubmit={(values) => {
            const { steps, workCenterId, ...rest } = values;
            return api
              .post("/manufacturing/rework-orders", {
                ...rest,
                workOrderId: orderId,
                steps: parseSteps(String(steps ?? ""), (workCenterId as string | null) ?? null),
              })
              .then(refresh);
          }}
        />
      )}
    </>
  );
}
