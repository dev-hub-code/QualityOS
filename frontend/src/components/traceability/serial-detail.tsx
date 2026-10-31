"use client";

import { useQuery } from "@tanstack/react-query";
import { Details, PageHeader, QueryState, RecordLink, Section, StatusBadge } from "@/components/records/parts";
import { api } from "@/lib/api";
import type { SerialTrace } from "@/lib/types";

export function SerialDetail({ serial, backHref }: { serial: string; backHref: string }) {
  const path = `/traceability/serials/${encodeURIComponent(serial)}`;
  const { data, error } = useQuery({
    queryKey: [path],
    queryFn: () => api.get<SerialTrace>(path),
    retry: false,
  });
  const back = <RecordLink href={backHref}>Back to search</RecordLink>;
  if (!data) {
    return (
      <div className="space-y-4">
        {back}
        <QueryState error={error} />
      </div>
    );
  }
  return (
    <div className="space-y-6">
      {back}
      <PageHeader title={data.serialNumber} subtitle={`${data.productCode} – ${data.productName}`} />
      <Section title="Unit">
        <Details
          items={[
            ["Product", `${data.productCode} – ${data.productName}`],
            [
              "Batch",
              <RecordLink key="batch" href={`/traceability/${encodeURIComponent(data.batchNumber)}`}>
                {data.batchNumber}
              </RecordLink>,
            ],
            ["Batch status", <StatusBadge key="status" status={data.batchStatus} />],
            ["Made", data.manufacturedAt?.slice(0, 10) ?? "Not finished"],
          ]}
        />
      </Section>
      <Section title="Inspections">
        {data.inspections.length === 0 ? (
          <p className="text-sm text-muted-foreground">No inspections to show.</p>
        ) : (
          <ul className="divide-y text-sm">
            {data.inspections.map((inspection) => (
              <li key={inspection.id} className="flex flex-wrap items-center gap-2 py-2">
                <RecordLink href={`/inspections/${inspection.id}`}>{inspection.inspectionNumber}</RecordLink>
                {inspection.inspectionType}
                <StatusBadge status={inspection.finalResult ?? inspection.status} />
              </li>
            ))}
          </ul>
        )}
      </Section>
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
