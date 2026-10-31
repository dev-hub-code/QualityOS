"use client";

import { useRouter } from "next/navigation";
import { RecordDialog } from "@/components/records/record-dialog";
import { api } from "@/lib/api";
import type { FieldDef } from "@/lib/record-form";
import type { RecallDetail } from "@/lib/types";

const FIELDS: FieldDef[] = [
  { name: "recallDate", label: "Recall date", type: "date", required: true },
  { name: "reason", label: "Reason for recall", type: "textarea", required: true },
  { name: "instructions", label: "What customers must do", type: "textarea", required: true },
];

export function StartRecallDialog({ batchNumber, onClose }: { batchNumber: string; onClose: () => void }) {
  const router = useRouter();
  return (
    <RecordDialog
      title={`Start a recall of ${batchNumber}`}
      fields={FIELDS}
      onClose={onClose}
      onSubmit={async (values) => {
        const recall = await api.post<RecallDetail>("/recalls", { ...values, batchNumbers: [batchNumber] });
        router.push(`/recalls/${recall.id}`);
      }}
    />
  );
}
