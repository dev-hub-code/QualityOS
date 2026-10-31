"use client";

import { useQuery } from "@tanstack/react-query";
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
import type { DispatchReturn, Inspection, TemplateSummary } from "@/lib/types";

const TEMPLATES = "size=100";

function InspectDialog({ itemId, onClose }: { itemId: string; onClose: () => void }) {
  const router = useRouter();
  const refresh = useRefresh();
  const { data } = useQuery({
    queryKey: ["/inspection-templates", TEMPLATES],
    queryFn: () => api.get<PageResponse<TemplateSummary>>(`/inspection-templates?${TEMPLATES}`),
  });
  if (!data) return null;
  const fields: FieldDef[] = [
    {
      name: "templateId",
      label: "Template",
      type: "select",
      required: true,
      options: data.items
        .filter(
          (template) =>
            template.publishedVersion !== null && ["FQC", "OQC"].includes(template.inspectionType),
        )
        .map((template) => ({ value: template.id, label: `${template.code} – ${template.name}` })),
    },
  ];
  return (
    <RecordDialog
      title="Inspect returned goods"
      fields={fields}
      onClose={onClose}
      onSubmit={async (values) => {
        const created = await api.post<Inspection>("/inspections", { ...values, returnItemId: itemId });
        await refresh();
        router.push(`/inspections/${created.id}`);
      }}
    />
  );
}

export default function DispatchReversalPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/dispatch-reversals/${id}`;
  const canInspect = usePermission("INSPECTION_CREATE");
  const [inspecting, setInspecting] = useState<string | null>(null);
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
      <Section title="Details">
        <Details
          items={[
            ["Dispatch", reversal.dispatchNumber],
            ["Customer", reversal.customerName],
            ["Return date", reversal.reversalDate],
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
      {inspecting && <InspectDialog itemId={inspecting} onClose={() => setInspecting(null)} />}
    </div>
  );
}
