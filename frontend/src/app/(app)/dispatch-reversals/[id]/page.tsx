"use client";

import { useQuery } from "@tanstack/react-query";
import { useParams } from "next/navigation";
import { useState } from "react";
import {
  Details,
  PageHeader,
  QueryState,
  Section,
  StatusBadge,
  useRefresh,
} from "@/components/records/parts";
import { StartInspectionDialog } from "@/components/inspections/start-inspection-dialog";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { formatQuantity, type FieldDef } from "@/lib/record-form";
import type { DispatchReturn } from "@/lib/types";

const RECEIVE_FIELDS: FieldDef[] = [{ name: "receivedOn", label: "Date received", type: "date", required: true }];

export default function DispatchReversalPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/dispatch-reversals/${id}`;
  const canInspect = usePermission("INSPECTION_CREATE");
  const canManage = usePermission("TRACEABILITY_MANAGE");
  const refresh = useRefresh();
  const [inspecting, setInspecting] = useState<string | null>(null);
  const [receiving, setReceiving] = useState(false);
  const { data: reversal, error } = useQuery({ queryKey: [path], queryFn: () => api.get<DispatchReturn>(path) });
  if (!reversal) return <QueryState error={error} />;
  return (
    <div className="space-y-6">
      <PageHeader
        title={reversal.reversalNumber}
        subtitle={
          <span className="flex flex-wrap items-center gap-2">
            <StatusBadge status={reversal.status} />
            {reversal.returnReference}
          </span>
        }
      />
      {canManage && reversal.status === "EXPECTED" && (
        <div className="flex flex-wrap gap-2">
          <Button onClick={() => setReceiving(true)}>Mark received</Button>
          <Button variant="outline" onClick={() => api.post(`${path}/cancel`).then(refresh)}>
            Cancel return
          </Button>
        </div>
      )}
      {receiving && (
        <RecordDialog
          title="Goods received"
          fields={RECEIVE_FIELDS}
          onClose={() => setReceiving(false)}
          onSubmit={(values) => api.post(`${path}/receive`, values).then(refresh)}
        />
      )}
      <Section title="Details">
        <Details
          items={[
            ["Dispatch", reversal.dispatchNumber],
            ["Customer", reversal.customerName],
            [reversal.status === "EXPECTED" ? "Expected on" : "Date received", reversal.reversalDate],
            ["Reason", reversal.reason],
            ["Recorded by", reversal.recordedByName],
          ]}
        />
      </Section>
      <Section title="Returned goods">
        <ul className="divide-y text-sm">
          {reversal.items.map((item) => (
            <li key={item.id} className="flex flex-wrap items-center justify-between gap-3 py-2">
              <span>
                {item.batchNumber} · {item.productCode} × {formatQuantity(item.quantity)}
                {item.serialNumbers.length > 0 && ` · ${item.serialNumbers.join(", ")}`}
              </span>
              <span className="flex items-center gap-2">
                <StatusBadge status={item.disposition ?? item.status} />
                {canInspect && item.status === "RETURN_RECEIVED" && (
                  <Button size="sm" onClick={() => setInspecting(item.id)}>
                    Inspect
                  </Button>
                )}
              </span>
            </li>
          ))}
        </ul>
      </Section>
      {inspecting && (
        <StartInspectionDialog
          title="Inspect returned goods"
          subject={{ returnItemId: inspecting }}
          onClose={() => setInspecting(null)}
        />
      )}
    </div>
  );
}
