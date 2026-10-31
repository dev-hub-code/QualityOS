"use client";

import { useQuery } from "@tanstack/react-query";
import { useParams } from "next/navigation";
import { PageHeader, QueryState, RecordLink, StatusBadge } from "@/components/records/parts";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";
import { formatQuantity } from "@/lib/record-form";
import type { TraceReport } from "@/lib/types";

export default function RecallReportPage() {
  const { batchNumber } = useParams<{ batchNumber: string }>();
  const path = `/traceability/batches/${batchNumber}`;
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<TraceReport>(path) });
  if (!data) return <QueryState error={error} />;
  return (
    <div className="space-y-6">
      <PageHeader
        title={`Recall report: ${data.subject.batchNumber}`}
        subtitle="Everyone this batch reached, including customers who have returned it."
      >
        <Button variant="outline" className="print:hidden" onClick={() => window.print()}>
          Print
        </Button>
      </PageHeader>
      <div className="overflow-x-auto">
        <table className="w-full text-left text-sm">
          <thead>
            <tr className="border-b">
              <th className="py-2 pr-4 font-medium">Customer</th>
              <th className="py-2 pr-4 font-medium">Dispatch</th>
              <th className="py-2 pr-4 font-medium">Shipment</th>
              <th className="py-2 pr-4 text-right font-medium">Dispatched</th>
              <th className="py-2 pr-4 text-right font-medium">Returned</th>
              <th className="py-2 text-right font-medium">Still out</th>
            </tr>
          </thead>
          <tbody>
            {data.recall.map((line) => (
              <tr key={line.dispatchId} className="border-b">
                <td className="py-2 pr-4">{line.customerName}</td>
                <td className="py-2 pr-4">
                  <RecordLink href="/dispatches">{line.dispatchNumber}</RecordLink>
                </td>
                <td className="py-2 pr-4">
                  {line.shippedOn ?? <StatusBadge status="AWAITING_SHIPMENT" />}
                </td>
                <td className="py-2 pr-4 text-right">{formatQuantity(line.quantity)}</td>
                <td className="py-2 pr-4 text-right">{formatQuantity(line.returnedQuantity)}</td>
                <td className="py-2 text-right">{formatQuantity(line.outstandingQuantity)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {data.recall.length === 0 && <p className="text-sm text-muted-foreground">This batch has not been dispatched.</p>}
    </div>
  );
}
