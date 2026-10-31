"use client";

import { useQuery } from "@tanstack/react-query";
import { useParams, useRouter } from "next/navigation";
import { useState } from "react";
import {
  PageHeader,
  QueryState,
  RecordLink,
  Section,
  StatusBadge,
  useApprovedSuppliers,
  useMaterials,
} from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { formatQuantity, type FieldDef } from "@/lib/record-form";
import type { CorrectionDetail, TimelineEvent, TraceNode, TraceReport } from "@/lib/types";

function Node({ node }: { node: TraceNode }) {
  return (
    <li className="py-2 text-sm">
      <span className="flex flex-wrap items-center gap-2">
        <RecordLink href={`/traceability/${encodeURIComponent(node.batchNumber)}`}>{node.batchNumber}</RecordLink>
        <StatusBadge status={node.status} />
      </span>
      <p>
        {node.code} – {node.name} · {formatQuantity(node.quantity)}
      </p>
      {node.supplierName && (
        <p className="text-muted-foreground">
          {node.supplierName}, supplier batch {node.supplierBatch}, {node.receiptNumber}, {node.purchaseOrderNumber}
        </p>
      )}
    </li>
  );
}

function Chain({ title, nodes, empty }: { title: string; nodes: TraceNode[]; empty: string }) {
  return (
    <Section title={title}>
      {nodes.length === 0 && <p className="text-sm text-muted-foreground">{empty}</p>}
      <ul className="divide-y">
        {nodes.map((node) => (
          <Node key={node.batchNumber} node={node} />
        ))}
      </ul>
    </Section>
  );
}

function Timeline({ events }: { events: TimelineEvent[] }) {
  return (
    <Section title="Timeline">
      <ol className="space-y-4 border-l pl-4 text-sm">
        {events.map((event, index) => (
          <li key={index} className="relative">
            <span className="absolute top-1.5 -left-[21px] size-2.5 rounded-full bg-primary" aria-hidden />
            <p className="text-muted-foreground">{new Date(event.at).toLocaleString()}</p>
            <p className="flex flex-wrap items-center gap-2">
              {event.link ? <RecordLink href={event.link}>{event.title}</RecordLink> : event.title}
              {event.status && <StatusBadge status={event.status} />}
            </p>
            {event.batchNumber && <p className="text-muted-foreground">{event.batchNumber}</p>}
          </li>
        ))}
      </ol>
    </Section>
  );
}

function CorrectLotDialog({ lot, onClose }: { lot: TraceNode; onClose: () => void }) {
  const router = useRouter();
  const materials = useMaterials();
  const suppliers = useApprovedSuppliers();
  const fields: FieldDef[] = [
    { name: "materialId", label: "Correct material", type: "select", options: materials },
    { name: "supplierId", label: "Correct supplier (changes the whole receipt)", type: "select", options: suppliers },
    { name: "supplierBatch", label: "Correct supplier batch" },
    { name: "quantity", label: "Correct quantity", type: "decimal" },
    { name: "reason", label: "Why this record is wrong", type: "textarea", required: true },
  ];
  return (
    <RecordDialog
      title={`Request correction of ${lot.batchNumber}`}
      fields={fields}
      onClose={onClose}
      onSubmit={async (values) => {
        const created = await api.post<CorrectionDetail>("/material-corrections", { ...values, receiptItemId: lot.id });
        router.push(`/material-corrections/${created.id}`);
      }}
    />
  );
}

export default function TraceReportPage() {
  const { batchNumber } = useParams<{ batchNumber: string }>();
  const path = `/traceability/batches/${batchNumber}`;
  const canCorrect = usePermission("MATERIAL_CORRECTION_REQUEST");
  const [correcting, setCorrecting] = useState(false);
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<TraceReport>(path) });
  if (!data) return <QueryState error={error} />;
  const { subject, backward, forward, dispatches, returns, affectedCustomers, serialNumbers, timeline } = data;
  return (
    <div className="space-y-6">
      <PageHeader title={subject.batchNumber} subtitle={`${subject.code} – ${subject.name}`}>
        {canCorrect && subject.kind === "LOT" && (
          <Button variant="outline" onClick={() => setCorrecting(true)}>
            Request correction
          </Button>
        )}
      </PageHeader>
      {correcting && <CorrectLotDialog lot={subject} onClose={() => setCorrecting(false)} />}
      {affectedCustomers.length > 0 && (
        <p className="rounded-lg border p-3 text-sm">
          <span className="font-medium">Customers who received this:</span> {affectedCustomers.join(", ")}
        </p>
      )}
      <div className="grid gap-6 lg:grid-cols-2">
        <Chain title="Backward: where it came from" nodes={[subject, ...backward]} empty="" />
        <Chain title="Forward: where it went" nodes={forward} empty="Not used in any batch yet." />
      </div>
      {dispatches.length > 0 && (
        <Section title="Dispatches">
          <ul className="divide-y text-sm">
            {dispatches.map((dispatch) => (
              <li key={dispatch.id} className="py-2">
                {dispatch.dispatchNumber} to {dispatch.customerName} on {dispatch.dispatchedOn}:{" "}
                {dispatch.lines.map((line) => `${line.batchNumber} × ${formatQuantity(line.quantity)}`).join(", ")}
                {dispatch.reversalReason && ` (reversed: ${dispatch.reversalReason})`}
              </li>
            ))}
          </ul>
        </Section>
      )}
      {returns.length > 0 && (
        <Section title="Returns">
          <ul className="divide-y text-sm">
            {returns.map((item) => (
              <li key={item.id} className="flex flex-wrap items-center gap-2 py-2">
                <RecordLink href={`/dispatch-reversals/${item.id}`}>{item.reversalNumber}</RecordLink>
                {item.returnReference} from {item.customerName}
                <StatusBadge status={item.status} />
              </li>
            ))}
          </ul>
        </Section>
      )}
      {serialNumbers.length > 0 && (
        <Section title="Serial numbers">
          <p className="text-sm break-words">{serialNumbers.join(", ")}</p>
        </Section>
      )}
      <Timeline events={timeline} />
    </div>
  );
}
