"use client";

import { useQuery } from "@tanstack/react-query";
import { createColumnHelper } from "@tanstack/react-table";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { DataTable } from "@/components/records/data-table";
import { PageHeader, RecordLink, Section, StatusBadge, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api, type PageResponse } from "@/lib/api";
import { formatQuantity, type FieldDef } from "@/lib/record-form";
import type { Inspection, InspectionSummary, ReceivedBatch, TemplateSummary } from "@/lib/types";

const column = createColumnHelper<InspectionSummary>();
const columns = [
  column.accessor("inspectionNumber", {
    header: "Number",
    cell: (info) => <RecordLink href={`/inspections/${info.row.original.id}`}>{info.getValue()}</RecordLink>,
  }),
  column.accessor("materialName", { header: "Material" }),
  column.accessor("batchNumber", { header: "Lot" }),
  column.accessor("supplierName", { header: "Supplier" }),
  column.accessor("status", { header: "Status", cell: (info) => <StatusBadge status={info.getValue()} /> }),
  column.accessor("disposition", {
    header: "Disposition",
    cell: (info) => {
      const value = info.getValue();
      return value ? <StatusBadge status={value} /> : "—";
    },
  }),
];

function StartDialog({ batch, onClose }: { batch: ReceivedBatch; onClose: () => void }) {
  const router = useRouter();
  const refresh = useRefresh();
  const query = `size=100&materialId=${batch.materialId}`;
  const { data } = useQuery({
    queryKey: ["/inspection-templates", query],
    queryFn: () => api.get<PageResponse<TemplateSummary>>(`/inspection-templates?${query}`),
  });
  const published = (data?.items ?? []).filter((template) => template.publishedVersion !== null);
  const fields: FieldDef[] = [
    {
      name: "templateId",
      label: "Template",
      type: "select",
      required: true,
      options: published.map((template) => ({
        value: template.id,
        label: `${template.code} – ${template.name}`,
      })),
    },
  ];
  if (!data) return null;
  return (
    <RecordDialog
      title={`Inspect ${batch.internalBatch}`}
      fields={fields}
      onClose={onClose}
      onSubmit={async (values) => {
        const created = await api.post<Inspection>("/inspections", { ...values, receiptItemId: batch.itemId });
        await refresh();
        router.push(`/inspections/${created.id}`);
      }}
    />
  );
}

function Queue() {
  const canCreate = usePermission("INSPECTION_CREATE");
  const [starting, setStarting] = useState<ReceivedBatch | null>(null);
  const path = "/material-receipts/awaiting-inspection";
  const { data } = useQuery({
    queryKey: [path],
    queryFn: () => api.get<PageResponse<ReceivedBatch>>(`${path}?size=50`),
  });
  const batches = data?.items ?? [];
  return (
    <Section title="Awaiting inspection">
      {batches.length === 0 && <p className="text-sm text-muted-foreground">Nothing is waiting.</p>}
      <ul className="divide-y text-sm">
        {batches.map((batch) => (
          <li key={batch.itemId} className="flex flex-wrap items-center justify-between gap-3 py-3">
            <div>
              <p className="font-medium">
                {batch.materialCode} – {batch.materialName}
              </p>
              <p className="text-muted-foreground">
                {batch.internalBatch} · {formatQuantity(batch.quantity)} · {batch.supplierName} · {batch.grnNumber}
              </p>
            </div>
            {canCreate && <Button onClick={() => setStarting(batch)}>Inspect</Button>}
          </li>
        ))}
      </ul>
      {starting && <StartDialog batch={starting} onClose={() => setStarting(null)} />}
    </Section>
  );
}

export default function InspectionsPage() {
  return (
    <div className="space-y-6">
      <PageHeader title="Inspections" />
      <Queue />
      <DataTable
        path="/inspections"
        columns={columns}
        searchLabel="Search by number or lot"
        emptyMessage="No inspections yet."
      />
    </div>
  );
}
