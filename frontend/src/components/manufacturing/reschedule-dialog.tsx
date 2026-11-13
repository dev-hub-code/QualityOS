"use client";

import { useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { api } from "@/lib/api";
import { PRIORITY_LABELS } from "@/lib/manufacturing";
import type { FieldDef } from "@/lib/record-form";
import type { ScheduledOrder, WorkOrderPriority } from "@/lib/types";

const FIELDS: FieldDef[] = [
  { name: "plannedStart", label: "Planned start", type: "date", required: true },
  { name: "plannedEnd", label: "Planned end", type: "date", required: true },
  {
    name: "priority",
    label: "Priority",
    type: "select",
    required: true,
    options: (Object.keys(PRIORITY_LABELS) as WorkOrderPriority[]).map((value) => ({ value, label: PRIORITY_LABELS[value] })),
  },
  { name: "reason", label: "Why it moves", type: "textarea", required: true },
];

/**
 * Moves an order's planned dates and priority. The board's warnings for the new dates are shown afterwards, since a
 * move that overloads a work centre is allowed: the planner decides.
 */
export function RescheduleDialog({
  order,
  onClose,
  onDone,
}: {
  order: { id: string; workOrderNumber: string; plannedStart: string; plannedEnd: string; priority: WorkOrderPriority };
  onClose: () => void;
  onDone?: (warnings: string[]) => void;
}) {
  const refresh = useRefresh();
  return (
    <RecordDialog
      title={`Reschedule ${order.workOrderNumber}`}
      fields={FIELDS}
      record={order}
      onClose={onClose}
      onSubmit={async (values) => {
        const { order: placed } = await api.post<{ order: ScheduledOrder }>(
          `/manufacturing/work-orders/${order.id}/reschedule`,
          values,
        );
        onDone?.(placed.warnings);
        await refresh();
      }}
    />
  );
}
