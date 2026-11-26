"use client";

import { useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { RecordDialog } from "@/components/records/record-dialog";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import type { FieldDef } from "@/lib/record-form";
import { recallBatchNumbers } from "@/lib/traceability";
import { today } from "@/lib/dates";
import type { CapaSummary, RecallDetail } from "@/lib/types";
import { lookupAll } from "@/components/records/parts";

const FIELDS: FieldDef[] = [
  { name: "otherBatches", label: "Other production batches in this recall (one per line)", type: "textarea" },
  { name: "recallDate", label: "Recall date", type: "date", required: true },
  { name: "reason", label: "Reason for recall", type: "textarea", required: true },
  { name: "instructions", label: "What customers must do", type: "textarea", required: true },
];

const CAPAS = "/capas";

/** Starts from the batch being traced. Batches with the same fault can join it, so their customers get one recall. */
export function StartRecallDialog({ batchNumber, onClose }: { batchNumber: string; onClose: () => void }) {
  const router = useRouter();
  const canSeeCapas = usePermission("CAPA_VIEW");
  const { data: capas } = useQuery({
    queryKey: [CAPAS],
    queryFn: () => lookupAll<CapaSummary>(CAPAS),
    enabled: canSeeCapas,
  });
  const fields: FieldDef[] = canSeeCapas
    ? [
        ...FIELDS,
        {
          name: "capaId",
          label: "CAPA behind this recall",
          type: "select",
          options: (capas ?? []).map((capa) => ({ value: capa.id, label: `${capa.capaNumber} – ${capa.title}` })),
        },
      ]
    : FIELDS;
  return (
    <RecordDialog
      title={`Start a recall of ${batchNumber}`}
      fields={fields}
      record={{ recallDate: today() }}
      onClose={onClose}
      onSubmit={async ({ otherBatches, capaId, ...values }) => {
        const recall = await api.post<RecallDetail>("/recalls", {
          ...values,
          capaId: capaId || null,
          batchNumbers: recallBatchNumbers(batchNumber, String(otherBatches ?? "")),
        });
        router.push(`/recalls/${recall.id}`);
      }}
    />
  );
}
