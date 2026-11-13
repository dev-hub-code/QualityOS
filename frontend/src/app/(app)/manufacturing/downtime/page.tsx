"use client";

import { useQuery } from "@tanstack/react-query";
import type { ColumnDef } from "@tanstack/react-table";
import Link from "next/link";
import { useState } from "react";
import { FormError } from "@/components/forms/field";
import { DataTable } from "@/components/records/data-table";
import { PageHeader, QueryState, Section, useAction, useMachines, useRefresh, useWorkCenters } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { formatMinutes, LOSS_CATEGORY_LABELS, localDate, toInstant } from "@/lib/manufacturing";
import type { FieldDef } from "@/lib/record-form";
import type { DowntimeGroupBy, DowntimeRecord, DowntimeSummary, LossCategory } from "@/lib/types";

const PATH = "/manufacturing/downtime";
const SELECT_CLASS = "h-9 rounded-xl border border-input bg-white/70 px-2 text-sm";

const SOURCE_LABELS = { SHOP_FLOOR: "Shop floor", MACHINE: "Machine status", MANUAL: "Entered by hand" } as const;
const GROUP_LABELS: Record<DowntimeGroupBy, string> = {
  MACHINE: "Machine",
  WORK_CENTER: "Work centre",
  PRODUCTION_LINE: "Production line",
  FACILITY: "Facility",
  CATEGORY: "Category",
};
/** A work order on hold is recorded only from the order itself. */
const CATEGORIES = (Object.keys(LOSS_CATEGORY_LABELS) as LossCategory[])
  .filter((category) => category !== "ORDER_ON_HOLD")
  .map((value) => ({ value, label: LOSS_CATEGORY_LABELS[value] }));

const when = (value: string | null) => (value ? new Date(value).toLocaleString() : "–");

/** A time typed as a date and a time; the API takes an instant. */
const timeFields = (prefix: "start" | "end", label: string, required: boolean): FieldDef[] => [
  { name: `${prefix}Date`, label: `${label} date`, type: "date", required },
  { name: `${prefix}Time`, label: `${label} time`, type: "time", required },
];

const NOTE_FIELDS: FieldDef[] = [
  { name: "reason", label: "Reason", type: "textarea", required: true },
  { name: "responsibleDepartment", label: "Responsible department" },
  { name: "comments", label: "Comments", type: "textarea" },
];

function splitInstant(value: string | null) {
  if (!value) return { date: "", time: "" };
  const at = new Date(value);
  return { date: localDate(0, at), time: at.toTimeString().slice(0, 5) };
}

function timesOf(values: Record<string, unknown>) {
  return { startedAt: toInstant(values.startDate, values.startTime), endedAt: toInstant(values.endDate, values.endTime) };
}

function Summary() {
  const [from, setFrom] = useState(localDate(-6));
  const [to, setTo] = useState(localDate());
  const [groupBy, setGroupBy] = useState<DowntimeGroupBy>("MACHINE");
  const { data, error } = useQuery({
    queryKey: [`${PATH}/summary`, from, to, groupBy],
    queryFn: () => api.get<DowntimeSummary>(`${PATH}/summary?from=${from}&to=${to}&groupBy=${groupBy}`),
    enabled: Boolean(from && to),
  });
  return (
    <Section title="Summary">
      <div className="mb-3 flex flex-wrap items-end gap-3 text-sm">
        <div className="space-y-1">
          <Label htmlFor="downtime-from">From</Label>
          <Input id="downtime-from" type="date" value={from} onChange={(event) => setFrom(event.target.value)} />
        </div>
        <div className="space-y-1">
          <Label htmlFor="downtime-to">To</Label>
          <Input id="downtime-to" type="date" value={to} onChange={(event) => setTo(event.target.value)} />
        </div>
        <div className="space-y-1">
          <Label htmlFor="downtime-group">By</Label>
          <select
            id="downtime-group"
            className={SELECT_CLASS}
            value={groupBy}
            onChange={(event) => setGroupBy(event.target.value as DowntimeGroupBy)}
          >
            {(Object.keys(GROUP_LABELS) as DowntimeGroupBy[]).map((key) => (
              <option key={key} value={key}>
                {GROUP_LABELS[key]}
              </option>
            ))}
          </select>
        </div>
      </div>
      {!data ? (
        <QueryState error={error} />
      ) : (
        <>
          <p className="mb-2 text-sm text-muted-foreground">
            {formatMinutes(data.totalMinutes)} down in all: {formatMinutes(data.unplannedMinutes)} unplanned,{" "}
            {formatMinutes(data.plannedMinutes)} planned. Days are in {data.timezone}; stops still open count to now.
          </p>
          {data.rows.length === 0 ? (
            <p className="text-sm text-muted-foreground">No downtime in this period.</p>
          ) : (
            <table className="w-full text-sm">
              <thead className="text-left text-muted-foreground">
                <tr>
                  <th className="py-1 font-medium">{GROUP_LABELS[data.groupBy]}</th>
                  <th className="py-1 font-medium">Stops</th>
                  <th className="py-1 font-medium">Unplanned</th>
                  <th className="py-1 font-medium">Planned</th>
                  <th className="py-1 font-medium">Total</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {data.rows.map((row) => (
                  <tr key={row.key}>
                    <td className="py-1.5">
                      {data.groupBy === "CATEGORY" ? LOSS_CATEGORY_LABELS[row.key as LossCategory] ?? row.label : row.label}
                    </td>
                    <td className="py-1.5">{row.stops}</td>
                    <td className="py-1.5">{formatMinutes(row.unplannedMinutes)}</td>
                    <td className="py-1.5">{formatMinutes(row.plannedMinutes)}</td>
                    <td className="py-1.5 font-medium">{formatMinutes(row.minutes)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </Section>
  );
}

/** Stops recorded on the floor, from machine status changes, and by hand. */
export default function DowntimePage() {
  const canManage = usePermission("DOWNTIME_MANAGE");
  const machines = useMachines();
  const workCenters = useWorkCenters();
  const refresh = useRefresh();
  const { run, pending, message } = useAction();
  const [openOnly, setOpenOnly] = useState(false);
  const [category, setCategory] = useState("");
  const [dialog, setDialog] = useState<"record" | DowntimeRecord | null>(null);
  const filter = [openOnly && "open=true", category && `category=${category}`].filter(Boolean).join("&");

  const columns: ColumnDef<DowntimeRecord>[] = [
    { header: "Started", cell: ({ row }) => when(row.original.startedAt) },
    {
      header: "Where",
      cell: ({ row }) => [row.original.machineCode, row.original.workCenterCode].filter(Boolean).join(" · "),
    },
    {
      header: "Category",
      cell: ({ row }) => `${LOSS_CATEGORY_LABELS[row.original.category]}${row.original.planned ? " (planned)" : ""}`,
    },
    { header: "Length", cell: ({ row }) => (row.original.endedAt ? formatMinutes(row.original.minutes) : `Open · ${formatMinutes(row.original.minutes)}`) },
    { header: "Reason", accessorKey: "reason" },
    { header: "Responsible", cell: ({ row }) => row.original.responsibleDepartment ?? "–" },
    {
      header: "Work order",
      cell: ({ row }) =>
        row.original.workOrderId ? (
          <Link className="text-primary hover:underline" href={`/manufacturing/work-orders/${row.original.workOrderId}`}>
            {row.original.workOrderNumber}
            {row.original.operationSequence !== null && row.original.operationSequence !== undefined && ` · op ${row.original.operationSequence}`}
          </Link>
        ) : (
          "–"
        ),
    },
    { header: "Source", cell: ({ row }) => SOURCE_LABELS[row.original.source] },
    ...(canManage
      ? [
          {
            header: "",
            id: "actions",
            cell: ({ row }: { row: { original: DowntimeRecord } }) => (
              <span className="flex gap-2">
                <Button size="sm" variant="outline" onClick={() => setDialog(row.original)}>
                  Change
                </Button>
                {row.original.source === "MANUAL" && !row.original.endedAt && (
                  <Button
                    size="sm"
                    disabled={pending}
                    onClick={() => run(() => api.post(`${PATH}/${row.original.id}/end`, {}))}
                  >
                    End now
                  </Button>
                )}
              </span>
            ),
          } satisfies ColumnDef<DowntimeRecord>,
        ]
      : []),
  ];

  const recordFields: FieldDef[] = [
    { name: "machineId", label: "Machine", type: "select", options: machines },
    { name: "workCenterId", label: "Or work centre, if no one machine stopped", type: "select", options: workCenters },
    { name: "category", label: "Category", type: "select", required: true, options: CATEGORIES },
    ...timeFields("start", "Start", true),
    ...timeFields("end", "End (leave blank while it lasts)", false),
    ...NOTE_FIELDS,
  ];

  // A stop recorded as it happened keeps its times and category; only the notes on it change.
  const editFields = (record: DowntimeRecord): FieldDef[] =>
    record.source === "MANUAL"
      ? [
          { name: "category", label: "Category", type: "select", required: true, options: CATEGORIES },
          ...timeFields("start", "Start", true),
          ...timeFields("end", "End", false),
          ...NOTE_FIELDS,
        ]
      : NOTE_FIELDS;

  const editRecord = (record: DowntimeRecord) => {
    const start = splitInstant(record.startedAt);
    const end = splitInstant(record.endedAt);
    return { ...record, startDate: start.date, startTime: start.time, endDate: end.date, endTime: end.time };
  };

  return (
    <div className="space-y-6">
      <PageHeader title="Downtime" subtitle="Every stop, from the shop floor, machine status changes and entries by hand.">
        {canManage && <Button onClick={() => setDialog("record")}>Record downtime</Button>}
      </PageHeader>
      <FormError message={message} />
      <Summary />
      <Section title="Stops">
        <div className="mb-3 flex flex-wrap items-center gap-4 text-sm">
          <label className="flex items-center gap-2">
            <input type="checkbox" checked={openOnly} onChange={(event) => setOpenOnly(event.target.checked)} />
            Still open only
          </label>
          <select
            aria-label="Category"
            className={SELECT_CLASS}
            value={category}
            onChange={(event) => setCategory(event.target.value)}
          >
            <option value="">Every category</option>
            {(Object.keys(LOSS_CATEGORY_LABELS) as LossCategory[]).map((key) => (
              <option key={key} value={key}>
                {LOSS_CATEGORY_LABELS[key]}
              </option>
            ))}
          </select>
        </div>
        <DataTable path={PATH} columns={columns} filter={filter} emptyMessage="No downtime recorded." />
      </Section>
      {dialog === "record" && (
        <RecordDialog
          title="Record downtime"
          fields={recordFields}
          record={{ startDate: localDate() }}
          onClose={() => setDialog(null)}
          onSubmit={(values) => {
            const { machineId, workCenterId, category: lost, reason, responsibleDepartment, comments } = values;
            return api
              .post(PATH, { machineId, workCenterId, category: lost, reason, responsibleDepartment, comments, ...timesOf(values) })
              .then(refresh);
          }}
        />
      )}
      {dialog !== null && dialog !== "record" && (
        <RecordDialog
          title={`Change the ${LOSS_CATEGORY_LABELS[dialog.category].toLowerCase()} stop`}
          fields={editFields(dialog)}
          record={editRecord(dialog)}
          onClose={() => setDialog(null)}
          onSubmit={(values) => {
            const notes = { reason: values.reason, responsibleDepartment: values.responsibleDepartment, comments: values.comments };
            const request = dialog.source === "MANUAL" ? { ...notes, category: values.category, ...timesOf(values) } : notes;
            return api.put(`${PATH}/${dialog.id}`, request).then(refresh);
          }}
        />
      )}
    </div>
  );
}
