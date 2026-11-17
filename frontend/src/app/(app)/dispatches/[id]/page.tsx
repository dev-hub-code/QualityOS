"use client";

import { useQuery } from "@tanstack/react-query";
import { useParams } from "next/navigation";
import { Details, PageHeader, QueryState, RecordLink, Section, StatusBadge } from "@/components/records/parts";
import { DispatchActions } from "@/components/traceability/dispatch-actions";
import { api } from "@/lib/api";
import { formatQuantity } from "@/lib/record-form";
import type { Dispatch, DispatchReturn } from "@/lib/types";

export default function DispatchPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/dispatches/${id}`;
  const { data: dispatch, error } = useQuery({ queryKey: [path], queryFn: () => api.get<Dispatch>(path) });
  const { data: returns = [] } = useQuery({
    queryKey: [`${path}/reversals`],
    queryFn: () => api.get<DispatchReturn[]>(`${path}/reversals`),
  });
  if (!dispatch) return <QueryState error={error} />;
  return (
    <div className="space-y-6">
      <PageHeader title={dispatch.dispatchNumber} subtitle={`To ${dispatch.customerName}, dispatched ${dispatch.dispatchedOn}`}>
        <div className="flex flex-wrap items-center gap-2">
          <DispatchActions dispatch={dispatch} />
        </div>
      </PageHeader>
      <Section title="Details">
        <Details
          items={[
            ["Customer", <RecordLink key="customer" href={`/customers/${dispatch.customerId}`}>{dispatch.customerName}</RecordLink>],
            ["Dispatched on", dispatch.dispatchedOn],
            ["Reference", dispatch.reference],
            ["Shipped on", dispatch.shippedOn],
            ["Carrier or vehicle", dispatch.carrierReference],
            ["Reversed as a mistake", dispatch.reversalReason],
          ]}
        />
      </Section>
      <Section title="Shipped">
        <ul className="divide-y text-sm">
          {dispatch.lines.map((line) => (
            <li key={line.id} className="flex flex-wrap items-center justify-between gap-3 py-2">
              <span>
                <RecordLink href={`/traceability/${encodeURIComponent(line.batchNumber)}`}>{line.batchNumber}</RecordLink>{" "}
                {line.productCode} – {line.productName}
              </span>
              <span className="text-muted-foreground">
                {formatQuantity(line.quantity)}
                {Number(line.returnedQuantity) > 0 && `, ${formatQuantity(line.returnedQuantity)} returned`}
                {Number(line.expectedQuantity) > 0 && `, ${formatQuantity(line.expectedQuantity)} on its way back`}
              </span>
            </li>
          ))}
        </ul>
      </Section>
      <Section title="Returns">
        {returns.length === 0 ? (
          <p className="text-sm text-muted-foreground">Nothing has come back.</p>
        ) : (
          <ul className="divide-y text-sm">
            {returns.map((returned) => (
              <li key={returned.id} className="flex flex-wrap items-center justify-between gap-3 py-2">
                <span>
                  <RecordLink href={`/dispatch-reversals/${returned.id}`}>{returned.reversalNumber}</RecordLink> –{" "}
                  {returned.reason}
                </span>
                <StatusBadge status={returned.status} />
              </li>
            ))}
          </ul>
        )}
      </Section>
    </div>
  );
}
