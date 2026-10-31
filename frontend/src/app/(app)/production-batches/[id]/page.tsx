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
} from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api, type PageResponse } from "@/lib/api";
import { formatQuantity, type FieldDef } from "@/lib/record-form";
import { inspectionTypeFor, parseSerials, toConsumeRequest } from "@/lib/traceability";
import type {
  BatchDetail,
  Inspection,
  ProductionBatch,
  ReleasedLot,
  TemplateSummary,
} from "@/lib/types";

const RELEASED = "/production-batches?status=RELEASED&size=100";
const LOTS = "/material-receipts/released-lots";

function ConsumeDialog({ path, batchId, onClose }: { path: string; batchId: string; onClose: () => void }) {
  const refresh = useRefresh();
  const { data: lots = [] } = useQuery({ queryKey: [LOTS], queryFn: () => api.get<ReleasedLot[]>(LOTS) });
  const { data: batches } = useQuery({
    queryKey: [RELEASED],
    queryFn: () => api.get<PageResponse<ProductionBatch>>(RELEASED),
  });
  const options = [
    ...lots.map((lot) => ({
      value: `lot:${lot.itemId}`,
      label: `${lot.internalBatch} – ${lot.materialCode} (${formatQuantity(lot.remainingQuantity)} left)`,
    })),
    ...(batches?.items ?? [])
      .filter((batch) => batch.id !== batchId && Number(batch.availableQuantity) > 0)
      .map((batch) => ({
        value: `batch:${batch.id}`,
        label: `${batch.batchNumber} – ${batch.productCode} (${formatQuantity(batch.availableQuantity)} left)`,
      })),
  ];
  const fields: FieldDef[] = [
    { name: "source", label: "Lot or batch", type: "select", required: true, options },
    { name: "quantity", label: "Quantity used", type: "decimal", required: true },
  ];
  return (
    <RecordDialog
      title="Record material used"
      fields={fields}
      onClose={onClose}
      onSubmit={(values) =>
        api
          .post(`${path}/consume`, toConsumeRequest(String(values.source), String(values.quantity)))
          .then(refresh)
      }
    />
  );
}

function InspectDialog({ batch, type, onClose }: { batch: ProductionBatch; type: string; onClose: () => void }) {
  const router = useRouter();
  const refresh = useRefresh();
  const query = `size=100&type=${type}`;
  const { data } = useQuery({
    queryKey: ["/inspection-templates", query],
    queryFn: () => api.get<PageResponse<TemplateSummary>>(`/inspection-templates?${query}`),
  });
  if (!data) return null;
  const fields: FieldDef[] = [
    {
      name: "templateId",
      label: "Template",
      type: "select",
      required: true,
      options: data.items
        .filter((template) => template.publishedVersion !== null)
        .map((template) => ({ value: template.id, label: `${template.code} – ${template.name}` })),
    },
  ];
  return (
    <RecordDialog
      title={`${type} inspection of ${batch.batchNumber}`}
      fields={fields}
      onClose={onClose}
      onSubmit={async (values) => {
        const created = await api.post<Inspection>("/inspections", { ...values, productionBatchId: batch.id });
        await refresh();
        router.push(`/inspections/${created.id}`);
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
  const [reversing, setReversing] = useState<string | null>(null);
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<BatchDetail>(path) });
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
            <Button onClick={() => setOpen("complete")}>Complete batch</Button>
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
            ["Planned", formatQuantity(batch.plannedQuantity)],
            ["Produced", batch.producedQuantity && formatQuantity(batch.producedQuantity)],
            ["Available", formatQuantity(batch.availableQuantity)],
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
                {formatQuantity(used.quantity)}
                {used.reversalReason && <span className="text-muted-foreground">Reversed: {used.reversalReason}</span>}
                {canManage && batch.status === "IN_PROGRESS" && !used.reversalReason && (
                  <Button variant="ghost" size="sm" onClick={() => setReversing(used.id)}>
                    Reverse
                  </Button>
                )}
              </span>
            </li>
          ))}
        </ul>
      </Section>
      {serialNumbers.length > 0 && (
        <Section title="Serial numbers">
          <p className="text-sm break-words">{serialNumbers.join(", ")}</p>
        </Section>
      )}
      {reversing && (
        <RecordDialog
          title="Reverse material used"
          fields={[{ name: "reason", label: "Why this entry was wrong", type: "textarea", required: true }]}
          onClose={() => setReversing(null)}
          onSubmit={(values) => api.post(`${path}/consumptions/${reversing}/reverse`, values).then(refresh)}
        />
      )}
      {open === "consume" && <ConsumeDialog path={path} batchId={id} onClose={close} />}
      {open === "inspect" && inspectionType && <InspectDialog batch={batch} type={inspectionType} onClose={close} />}
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
