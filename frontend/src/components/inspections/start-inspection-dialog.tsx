"use client";

import { useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { api } from "@/lib/api";
import type { FieldDef } from "@/lib/record-form";
import type { ApplicableTemplates, Inspection } from "@/lib/types";

/** What is being inspected: a received lot, a production batch, or returned goods. */
export type InspectionSubject = { receiptItemId: string } | { productionBatchId: string } | { returnItemId: string };

/**
 * Offers only the published templates that fit the subject, with the item's own first. When exactly one fits, it is
 * already chosen.
 */
export function StartInspectionDialog({
  title,
  subject,
  onClose,
}: {
  title: string;
  subject: InspectionSubject;
  onClose: () => void;
}) {
  const router = useRouter();
  const refresh = useRefresh();
  const query = new URLSearchParams(subject).toString();
  const { data } = useQuery({
    queryKey: ["/inspections/applicable-templates", query],
    queryFn: () => api.get<ApplicableTemplates>(`/inspections/applicable-templates?${query}`),
  });
  if (!data) return null;
  const fields: FieldDef[] = [
    {
      name: "templateId",
      label: "Template",
      type: "select",
      required: true,
      options: data.templates.map((template) => ({
        value: template.id,
        label:
          `${template.code} – ${template.name}` + (template.materialId || template.productId ? " · for this item" : ""),
      })),
    },
  ];
  return (
    <RecordDialog
      title={title}
      fields={fields}
      record={{ templateId: data.suggestedId ?? "" }}
      onClose={onClose}
      extra={
        data.templates.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            No published template fits this inspection. Publish one under Inspection templates first.
          </p>
        ) : undefined
      }
      onSubmit={async (values) => {
        const created = await api.post<Inspection>("/inspections", { ...values, ...subject });
        await refresh();
        router.push(`/inspections/${created.id}`);
      }}
    />
  );
}
