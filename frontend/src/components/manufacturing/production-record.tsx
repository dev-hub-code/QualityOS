"use client";

import { useQuery } from "@tanstack/react-query";
import { Details, RecordLink, Section, StatusBadge } from "@/components/records/parts";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { formatQuantity } from "@/lib/record-form";
import type { ProductionRecord } from "@/lib/types";

const when = (value: string | null) => (value ? new Date(value).toLocaleString() : "–");

/** The work order that made a batch, for those who may see work orders; null for a batch recorded by hand. */
export function useProductionRecord(batchId: string): ProductionRecord | null {
  const canView = usePermission("WORK_ORDER_VIEW");
  const { data } = useQuery({
    queryKey: ["/manufacturing/work-orders/production-record", batchId],
    enabled: canView,
    queryFn: () => api.get<ProductionRecord | null>(`/manufacturing/work-orders/production-record?batchId=${batchId}`),
  });
  return data ?? null;
}

/** How the batch was made: the order and its definitions, each operation's people and machines, quality and receipt. */
export function ProductionRecordPanel({ record }: { record: ProductionRecord }) {
  const kind = record.definitionKind === "RECIPE" ? "Recipe" : "BOM";
  return (
    <Section title="Production record">
      <Details
        items={[
          [
            "Work order",
            <span key="order" className="flex flex-wrap items-center gap-2">
              <RecordLink href={`/manufacturing/work-orders/${record.workOrderId}`}>{record.workOrderNumber}</RecordLink>
              <StatusBadge status={record.status} />
            </span>,
          ],
          ["Made from", record.definitionRevision === null ? "–" : `${kind} revision ${record.definitionRevision}`],
          ["Routing", record.routingRevision === null ? "–" : `Revision ${record.routingRevision}`],
          [
            "Received",
            record.receipt
              ? `${formatQuantity(record.receipt.quantity)} of ${formatQuantity(record.receipt.orderedQuantity)}, ${
                  record.receipt.receivedByName ?? "Former member"
                }, ${when(record.receipt.receivedAt)}`
              : "Not yet",
          ],
        ]}
      />
      {record.operations.length > 0 && (
        <>
          <h3 className="mt-4 mb-1 text-sm font-medium">Operations</h3>
          <ul className="divide-y text-sm">
            {record.operations.map((operation) => (
              <li key={operation.sequence} className="py-2">
                <span className="flex flex-wrap items-center justify-between gap-3">
                  <span>
                    {operation.sequence}. {operation.name}
                    {operation.workCenterCode && <span className="text-muted-foreground"> at {operation.workCenterCode}</span>}
                  </span>
                  <StatusBadge status={operation.status} />
                </span>
                <span className="block text-muted-foreground">
                  Good {formatQuantity(operation.goodQuantity)} · rejected {formatQuantity(operation.rejectedQuantity)} · scrapped{" "}
                  {formatQuantity(operation.scrapQuantity)}
                  {operation.operators.length > 0 && ` · by ${operation.operators.join(", ")}`}
                  {operation.machines.length > 0 && ` · on ${operation.machines.join(", ")}`}
                  {operation.completedAt && ` · done ${when(operation.completedAt)}`}
                </span>
              </li>
            ))}
          </ul>
        </>
      )}
      {record.gates.length > 0 && (
        <>
          <h3 className="mt-4 mb-1 text-sm font-medium">Quality gates</h3>
          <ul className="list-disc space-y-0.5 pl-5 text-sm">
            {record.gates.map((gate) => (
              <li key={gate}>{gate}</li>
            ))}
          </ul>
        </>
      )}
      {record.reworkOrders.length > 0 && (
        <>
          <h3 className="mt-4 mb-1 text-sm font-medium">Rework</h3>
          <ul className="list-disc space-y-0.5 pl-5 text-sm">
            {record.reworkOrders.map((rework) => (
              <li key={rework}>{rework}</li>
            ))}
          </ul>
        </>
      )}
    </Section>
  );
}
