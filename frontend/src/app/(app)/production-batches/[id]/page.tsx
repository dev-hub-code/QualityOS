"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useState } from "react";
import {
  Details,
  PageHeader,
  QueryState,
  Section,
  StatusBadge,
  useRefresh,
  useUnits,
} from "@/components/records/parts";
import { ProductionRecordPanel, useProductionRecord } from "@/components/manufacturing/production-record";
import { StartInspectionDialog } from "@/components/inspections/start-inspection-dialog";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api, type PageResponse } from "@/lib/api";
import { freeQuantity } from "@/lib/manufacturing";
import { formatQuantity, type FieldDef } from "@/lib/record-form";
import { inspectionTypeFor, parseSerials, toConsumeRequest } from "@/lib/traceability";
import type {
  BatchDetail,
  CorrectionDetail,
  ProductionBatch,
  ReleasedLot,
} from "@/lib/types";

const RELEASED = "/production-batches?status=RELEASED&size=100";
const LOTS = "/material-receipts/released-lots";

/** A quantity with its unit's code, such as "7.5 KG". */
function withUnit(quantity: string, unit: string) {
  return unit ? `${formatQuantity(quantity)} ${unit}` : formatQuantity(quantity);
}

function ConsumeDialog({ path, batchId, onClose }: { path: string; batchId: string; onClose: () => void }) {
  const refresh = useRefresh();
  const units = useUnits();
  const { data: lots = [] } = useQuery({ queryKey: [LOTS], queryFn: () => api.get<ReleasedLot[]>(LOTS) });
  const { data: batches } = useQuery({
    queryKey: [RELEASED],
    queryFn: () => api.get<PageResponse<ProductionBatch>>(RELEASED),
  });
  const options = [
    ...lots.map((lot) => ({
      value: `lot:${lot.itemId}`,
      label: `${lot.internalBatch} – ${lot.materialCode} (${withUnit(freeQuantity(lot), units.label(lot.uomId))} free)`,
    })),
    ...(batches?.items ?? [])
      .filter((batch) => batch.id !== batchId && Number(batch.availableQuantity) > 0)
      .map((batch) => ({
        value: `batch:${batch.id}`,
        label: `${batch.batchNumber} – ${batch.productCode} (${withUnit(batch.availableQuantity, units.label(batch.uomId))} left)`,
      })),
  ];
  const fields: FieldDef[] = [
    { name: "source", label: "Lot or batch", type: "select", required: true, options },
    { name: "quantity", label: "Quantity used", type: "decimal", required: true },
    { name: "uomId", label: "Unit, if not the lot's or batch's own", type: "select", options: units.options },
  ];
  return (
    <RecordDialog
      title="Record material used"
      fields={fields}
      onClose={onClose}
      onSubmit={(values) =>
        api
          .post(
            `${path}/consume`,
            toConsumeRequest(String(values.source), String(values.quantity), String(values.uomId ?? "")),
          )
          .then(refresh)
      }
    />
  );
}

function CorrectConsumptionDialog({ consumptionId, onClose }: { consumptionId: string; onClose: () => void }) {
  const router = useRouter();
  const units = useUnits();
  const { data: lots = [] } = useQuery({ queryKey: [LOTS], queryFn: () => api.get<ReleasedLot[]>(LOTS) });
  const fields: FieldDef[] = [
    {
      name: "correctedLotId",
      label: "Lot actually used",
      type: "select",
      options: lots.map((lot) => ({
        value: lot.itemId,
        label: `${lot.internalBatch} – ${lot.materialCode} (${withUnit(freeQuantity(lot), units.label(lot.uomId))} free)`,
      })),
    },
    // A correction takes no unit of its own: it is in the unit of the lot or batch it draws on.
    { name: "quantity", label: "Quantity actually used, in that lot's own unit", type: "decimal" },
    { name: "reason", label: "Why this entry is wrong", type: "textarea", required: true },
  ];
  return (
    <RecordDialog
      title="Request correction"
      fields={fields}
      onClose={onClose}
      onSubmit={async (values) => {
        const created = await api.post<CorrectionDetail>("/material-corrections", { ...values, consumptionId });
        router.push(`/material-corrections/${created.id}`);
      }}
    />
  );
}

export default function ProductionBatchPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/production-batches/${id}`;
  const refresh = useRefresh();
  const canManage = usePermission("TRACEABILITY_MANAGE");
  const canInspect = usePermission("INSPECTION_CREATE");
  const [open, setOpen] = useState<"consume" | "complete" | "serials" | "inspect" | null>(null);
  const canCorrect = usePermission("MATERIAL_CORRECTION_REQUEST");
  const [reversing, setReversing] = useState<string | null>(null);
  const [correcting, setCorrecting] = useState<string | null>(null);
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<BatchDetail>(path) });
  const units = useUnits();
  const record = useProductionRecord(id);
  if (!data) return <QueryState error={error} />;

  const { batch, consumptions, serialNumbers } = data;
  const inspectionType = inspectionTypeFor(batch);
  const close = () => setOpen(null);
  return (
    <div className="space-y-6">
      <PageHeader
        title={batch.batchNumber}
        subtitle={
          <span className="flex flex-wrap items-center gap-2">
            <StatusBadge status={batch.status} />
            {batch.productCode} – {batch.productName}
          </span>
        }
      >
        <Button variant="outline" render={<Link href={`/traceability/${encodeURIComponent(batch.batchNumber)}`} />}>
          Trace
        </Button>
        {canManage && batch.status === "IN_PROGRESS" && (
          <>
            <Button variant="outline" onClick={() => setOpen("consume")}>
              Record material used
            </Button>
            {/* A work order's batch is completed by receiving the order's finished goods. */}
            {!record && <Button onClick={() => setOpen("complete")}>Complete batch</Button>}
          </>
        )}
        {canManage && batch.producedQuantity !== null && (
          <Button variant="outline" onClick={() => setOpen("serials")}>
            Add serial numbers
          </Button>
        )}
        {canInspect && inspectionType && <Button onClick={() => setOpen("inspect")}>Inspect</Button>}
      </PageHeader>
      <Section title="Details">
        <Details
          items={[
            ["Planned", withUnit(batch.plannedQuantity, units.label(batch.uomId))],
            ["Produced", batch.producedQuantity && withUnit(batch.producedQuantity, units.label(batch.uomId))],
            ["Available", withUnit(batch.availableQuantity, units.label(batch.uomId))],
          ]}
        />
      </Section>
      <Section title="Material used">
        {consumptions.length === 0 && <p className="text-sm text-muted-foreground">Nothing recorded yet.</p>}
        <ul className="divide-y text-sm">
          {consumptions.map((used) => (
            <li key={used.id} className="flex flex-wrap items-center justify-between gap-3 py-2">
              <span className={used.reversalReason ? "text-muted-foreground line-through" : undefined}>
                {used.sourceBatch} · {used.description}
              </span>
              <span className="flex shrink-0 items-center gap-2">
                <span>
                  {withUnit(used.quantity, units.label(used.uomId))}
                  {used.enteredQuantity && used.enteredUomId !== used.uomId && (
                    <span className="text-muted-foreground">
                      {" "}
                      (entered as {withUnit(used.enteredQuantity, units.label(used.enteredUomId))})
                    </span>
                  )}
                </span>
                {used.reversalReason && <span className="text-muted-foreground">Reversed: {used.reversalReason}</span>}
                {canManage && batch.status === "IN_PROGRESS" && !used.reversalReason && (
                  <Button variant="ghost" size="sm" onClick={() => setReversing(used.id)}>
                    Reverse
                  </Button>
                )}
                {canCorrect && !used.reversalReason && (
                  <Button variant="ghost" size="sm" onClick={() => setCorrecting(used.id)}>
                    Request correction
                  </Button>
                )}
              </span>
            </li>
          ))}
        </ul>
      </Section>
      {record && <ProductionRecordPanel record={record} />}
      {serialNumbers.length > 0 && (
        <Section title="Serial numbers">
          <p className="text-sm break-words">{serialNumbers.join(", ")}</p>
        </Section>
      )}
      {correcting && <CorrectConsumptionDialog consumptionId={correcting} onClose={() => setCorrecting(null)} />}
      {reversing && (
        <RecordDialog
          title="Reverse material used"
          fields={[{ name: "reason", label: "Why this entry was wrong", type: "textarea", required: true }]}
          onClose={() => setReversing(null)}
          onSubmit={(values) => api.post(`${path}/consumptions/${reversing}/reverse`, values).then(refresh)}
        />
      )}
      {open === "consume" && <ConsumeDialog path={path} batchId={id} onClose={close} />}
      {open === "inspect" && inspectionType && (
        <StartInspectionDialog
          title={`${inspectionType} inspection of ${batch.batchNumber}`}
          subject={{ productionBatchId: batch.id }}
          onClose={close}
        />
      )}
      {open === "complete" && (
        <RecordDialog
          title="Complete batch"
          fields={[{ name: "producedQuantity", label: "Produced quantity", type: "decimal", required: true }]}
          onClose={close}
          onSubmit={(values) => api.post(`${path}/complete`, values).then(refresh)}
        />
      )}
      {open === "serials" && (
        <RecordDialog
          title="Add serial numbers"
          fields={[{ name: "serials", label: "Serial numbers (one per line)", type: "textarea", required: true }]}
          onClose={close}
          onSubmit={(values) =>
            api.post(`${path}/serials`, { serialNumbers: parseSerials(String(values.serials)) }).then(refresh)
          }
        />
      )}
    </div>
  );
}
