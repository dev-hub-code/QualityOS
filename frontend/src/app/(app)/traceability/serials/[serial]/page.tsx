"use client";

import { useQuery } from "@tanstack/react-query";
import { useParams } from "next/navigation";
import { PageHeader, QueryState, RecordLink, Section, StatusBadge } from "@/components/records/parts";
import { api } from "@/lib/api";
import type { SerialTrace } from "@/lib/types";

export default function SerialTracePage() {
  const { serial } = useParams<{ serial: string }>();
  const path = `/traceability/serials/${serial}`;
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<SerialTrace>(path) });
  if (!data) return <QueryState error={error} />;
  return (
    <div className="space-y-6">
      <PageHeader title={data.serialNumber} subtitle={`Batch ${data.batchNumber}`} />
      <Section title="Where this unit went">
        {data.deliveries.length === 0 ? (
          <p className="text-sm text-muted-foreground">This unit has not been dispatched.</p>
        ) : (
          <ul className="divide-y text-sm">
            {data.deliveries.map((delivery) => (
              <li key={delivery.dispatchId} className="flex flex-wrap items-center gap-2 py-2">
                <RecordLink href="/dispatches">{delivery.dispatchNumber}</RecordLink>
                {delivery.customerName} on {delivery.shippedOn ?? delivery.dispatchedOn}
                <StatusBadge
                  status={delivery.returned ? "RETURNED" : delivery.shippedOn ? "SHIPPED" : "AWAITING_SHIPMENT"}
                />
              </li>
            ))}
          </ul>
        )}
      </Section>
    </div>
  );
}
