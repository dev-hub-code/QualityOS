"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { FormError } from "@/components/forms/field";
import { PageHeader, QueryState, Section, useAction, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { useStates } from "@/lib/places";
import type { FieldDef } from "@/lib/record-form";
import type { StateHoliday } from "@/lib/types";

const SELECT_CLASS = "h-9 rounded-xl border border-input bg-white/70 px-2 text-sm";
const ALL_INDIA = "ALL";

const FIELDS: FieldDef[] = [
  { name: "date", label: "Date", type: "date", required: true },
  { name: "name", label: "Name", required: true },
];

/** "2026-01-26" as "Mon, 26 Jan", with no time zone to move it. */
function showDate(date: string): string {
  const [year, month, day] = date.split("-").map(Number);
  return new Date(year, month - 1, day).toLocaleDateString(undefined, {
    weekday: "short",
    day: "numeric",
    month: "short",
  });
}

export default function StateHolidaysPage() {
  const confirm = useConfirm();
  const canManage = usePermission("FACILITY_MANAGE");
  const thisYear = new Date().getFullYear();
  const [region, setRegion] = useState(ALL_INDIA);
  const [year, setYear] = useState(thisYear);
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<StateHoliday | null>(null);
  const { run, message } = useAction();
  const refresh = useRefresh();
  const states = useStates(true);
  const path = `/state-holidays?region=${region}&year=${year}`;
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<StateHoliday[]>(path) });
  const regionName =
    region === ALL_INDIA ? "All India" : (states.data?.find((state) => state.gstCode === region)?.name ?? region);
  const save = (values: Record<string, unknown>, holiday?: StateHoliday) =>
    (holiday
      ? api.put(`/state-holidays/${holiday.id}`, { ...values, region })
      : api.post("/state-holidays", { ...values, region })
    ).then(refresh);

  return (
    <div className="max-w-3xl space-y-6">
      <PageHeader title="State holidays" subtitle="Every facility in a state takes its holidays, and All India's">
        <select
          aria-label="State"
          className={SELECT_CLASS}
          value={region}
          onChange={(event) => setRegion(event.target.value)}
        >
          <option value={ALL_INDIA}>All India</option>
          {states.data?.map((state) => (
            <option key={state.gstCode} value={state.gstCode}>
              {state.name}
            </option>
          ))}
        </select>
        <select
          aria-label="Year"
          className={SELECT_CLASS}
          value={year}
          onChange={(event) => setYear(Number(event.target.value))}
        >
          {[thisYear - 1, thisYear, thisYear + 1].map((option) => (
            <option key={option} value={option}>
              {option}
            </option>
          ))}
        </select>
      </PageHeader>
      <FormError message={message} />
      <Section
        title={`${regionName} holidays in ${year}`}
        action={
          canManage && (
            <Button variant="outline" size="sm" onClick={() => setAdding(true)}>
              Add holiday
            </Button>
          )
        }
      >
        <p className="mb-2 text-sm text-muted-foreground">
          {region === ALL_INDIA
            ? "Every facility closes on these, whatever its state, unless it is set to work that day."
            : `Every facility whose address is in ${regionName} closes on these, unless it is set to work that day.`}
        </p>
        {!data ? (
          <QueryState error={error} />
        ) : data.length === 0 ? (
          <p className="text-sm text-muted-foreground">No holidays yet.</p>
        ) : (
          <ul className="divide-y text-sm">
            {data.map((holiday) => (
              <li key={holiday.id} className="flex items-center justify-between gap-4 py-2">
                <span className="flex items-baseline gap-3">
                  <span className="w-28 shrink-0 font-medium">{showDate(holiday.date)}</span>
                  {holiday.name}
                </span>
                {canManage && (
                  <span className="flex gap-1">
                    <Button variant="ghost" size="sm" onClick={() => setEditing(holiday)}>
                      Edit
                    </Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() =>
                        confirm({
                          title: `Remove ${holiday.name}?`,
                          description: `Facilities in ${holiday.regionName} will work on ${holiday.date}.`,
                          confirmLabel: "Remove holiday",
                          danger: true,
                        }).then((ok) => {
                          if (ok) void run(() => api.delete(`/state-holidays/${holiday.id}`));
                        })
                      }
                    >
                      Remove
                    </Button>
                  </span>
                )}
              </li>
            ))}
          </ul>
        )}
      </Section>
      {adding && (
        <RecordDialog
          title={`Add a ${regionName} holiday`}
          fields={FIELDS}
          onClose={() => setAdding(false)}
          onSubmit={(values) => save(values)}
        />
      )}
      {editing && (
        <RecordDialog
          title="Edit holiday"
          fields={FIELDS}
          record={editing}
          onClose={() => setEditing(null)}
          onSubmit={(values) => save(values, editing)}
        />
      )}
    </div>
  );
}
