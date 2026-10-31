"use client";

import { useQuery } from "@tanstack/react-query";
import { useParams } from "next/navigation";
import { PageHeader, QueryState, RecordLink, Section, StatusBadge } from "@/components/records/parts";
import { api } from "@/lib/api";
import { formatQuantity } from "@/lib/record-form";
import type { TimelineEvent, TraceNode, TraceReport } from "@/lib/types";

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

export default function TraceReportPage() {
  const { batchNumber } = useParams<{ batchNumber: string }>();
  const path = `/traceability/batches/${batchNumber}`;
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<TraceReport>(path) });
  if (!data) return <QueryState error={error} />;
  const { subject, backward, forward, dispatches, affectedCustomers, serialNumbers, timeline } = data;
  return (
    <div className="space-y-6">
      <PageHeader title={subject.batchNumber} subtitle={`${subject.code} – ${subject.name}`} />
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
