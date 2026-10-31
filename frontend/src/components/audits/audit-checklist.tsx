"use client";

import { FormError } from "@/components/forms/field";
import { StatusBadge, useAction } from "@/components/records/parts";
import { api } from "@/lib/api";
import type { AuditItem } from "@/lib/types";

const RESULTS = [
  ["CONFORMING", "Conforming"],
  ["NONCONFORMING", "Nonconforming"],
  ["NOT_APPLICABLE", "Not applicable"],
];

export function AuditChecklist({ auditId, items, editable }: { auditId: string; items: AuditItem[]; editable: boolean }) {
  const { run, message } = useAction();
  return (
    <>
      <FormError message={message} />
      <ol className="divide-y text-sm">
        {items.map((item) => (
          <li key={item.id} className="flex flex-wrap items-center justify-between gap-2 py-2">
            <span className="min-w-0 break-words">
              {item.position}. {item.question}
              {item.clauseReference && <span className="text-muted-foreground"> ({item.clauseReference})</span>}
            </span>
            {editable ? (
              <select
                aria-label={`Answer to question ${item.position}`}
                className="h-8 rounded-md border bg-background px-2 text-sm"
                value={item.result ?? ""}
                onChange={(event) => {
                  const result = event.target.value;
                  if (result) run(() => api.put(`/audits/${auditId}/items/${item.id}`, { result, note: item.note }));
                }}
              >
                <option value="">Not answered</option>
                {RESULTS.map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>
            ) : (
              item.result && <StatusBadge status={item.result} />
            )}
          </li>
        ))}
      </ol>
    </>
  );
}
