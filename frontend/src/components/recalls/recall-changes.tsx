"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { lookupAll, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import type { FieldDef } from "@/lib/record-form";
import type { CapaSummary, RecallDetail } from "@/lib/types";

const BATCH_FIELDS: FieldDef[] = [
  { name: "batchNumbers", label: "Batch numbers, one per line or separated by commas", type: "textarea", required: true },
  { name: "reason", label: "Why they belong to this recall", type: "textarea", required: true },
];

/** While a recall is open, batches found later can join it, and the CAPA behind it can be linked or changed. */
export function RecallChanges({ recall, path }: { recall: RecallDetail; path: string }) {
  const canManage = usePermission("RECALL_MANAGE");
  const canSeeCapas = usePermission("CAPA_VIEW");
  const refresh = useRefresh();
  const [dialog, setDialog] = useState<"batches" | "capa" | null>(null);
  const { data: capas } = useQuery({
    queryKey: ["/capas"],
    queryFn: () => lookupAll<CapaSummary>("/capas"),
    enabled: dialog === "capa",
  });
  if (!canManage || recall.status !== "OPEN") return null;
  const capaFields: FieldDef[] = [
    {
      name: "capaId",
      label: "CAPA behind this recall",
      type: "select",
      options: (capas ?? []).map((capa) => ({ value: capa.id, label: `${capa.capaNumber} – ${capa.title}` })),
    },
  ];
  return (
    <>
      <Button variant="outline" onClick={() => setDialog("batches")}>
        Add batches
      </Button>
      {canSeeCapas && (
        <Button variant="outline" onClick={() => setDialog("capa")}>
          {recall.capaId ? "Change CAPA" : "Link CAPA"}
        </Button>
      )}
      {dialog === "batches" && (
        <RecordDialog
          title="Add batches to this recall"
          fields={BATCH_FIELDS}
          onClose={() => setDialog(null)}
          onSubmit={async ({ batchNumbers, reason }) => {
            await api.post(`${path}/batches`, {
              batchNumbers: String(batchNumbers ?? "")
                .split(/[\s,]+/)
                .filter(Boolean),
              reason,
            });
            refresh();
          }}
        />
      )}
      {dialog === "capa" && capas && (
        <RecordDialog
          title="CAPA behind this recall"
          fields={capaFields}
          record={{ capaId: recall.capaId }}
          onClose={() => setDialog(null)}
          onSubmit={async ({ capaId }) => {
            await api.put(`${path}/capa`, { capaId: capaId || null });
            refresh();
          }}
        />
      )}
    </>
  );
}
