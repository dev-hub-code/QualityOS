"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useState } from "react";
import { FormError } from "@/components/forms/field";
import { Details, PageHeader, QueryState, Section, useAction, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { daysToMask, describeAlternateOff, describeDays, maskToDays, WEEK_ORDINALS, WEEKDAYS } from "@/lib/manufacturing";
import { FACILITY_FIELDS } from "@/lib/record-fields";
import type { FieldDef } from "@/lib/record-form";
import type { FacilityDetail } from "@/lib/types";
import { useConfirm } from "@/components/ui/confirm-dialog";

const UNIT_FIELDS: FieldDef[] = [
  { name: "code", label: "Code", required: true },
  { name: "name", label: "Name", required: true },
];
const SHIFT_FIELDS: FieldDef[] = [
  { name: "name", label: "Name", required: true },
  { name: "startsAt", label: "Starts at", type: "time", required: true },
  { name: "endsAt", label: "Ends at", type: "time", required: true },
  { name: "breakMinutes", label: "Break, in minutes", type: "decimal" },
  ...WEEKDAYS.map((day) => ({ name: `day${day}`, label: `Works on ${day}`, type: "checkbox" as const })),
];

/** The form's day boxes as the API's days of the week. */
function shiftRequest(values: Record<string, unknown>) {
  const { name, startsAt, endsAt, breakMinutes } = values;
  return { name, startsAt, endsAt, breakMinutes: breakMinutes ?? 0, daysOfWeek: daysToMask(WEEKDAYS.map((day) => values[`day${day}`] === true)) };
}

const SHIFT_DEFAULTS = { breakMinutes: "0", ...Object.fromEntries(WEEKDAYS.map((day) => [`day${day}`, true])) };

type Dialog = "edit" | "department" | "line" | "shift" | "holiday" | "weeklyOff" | null;

const WEEKLY_OFF_FIELDS: FieldDef[] = [
  ...WEEKDAYS.map((day) => ({ name: `day${day}`, label: `Off every ${day}`, type: "checkbox" as const })),
  {
    name: "alternateDay",
    label: "Alternate day off (only in some weeks of the month)",
    type: "select",
    options: WEEKDAYS.map((day, index) => ({ value: String(index + 1), label: day })),
  },
  ...WEEK_ORDINALS.map((ordinal) => ({
    name: `week${ordinal}`,
    label: `Off on the ${ordinal} one in the month`,
    type: "checkbox" as const,
  })),
];

/** The dialog's values for a facility's days off. */
function weeklyOffRecord(weeklyOffDays: number, alternateOffDay: number | null, alternateOffWeeks: number) {
  return {
    ...Object.fromEntries(maskToDays(weeklyOffDays).map((off, index) => [`day${WEEKDAYS[index]}`, off])),
    alternateDay: alternateOffDay === null ? "" : String(alternateOffDay),
    ...Object.fromEntries(WEEK_ORDINALS.map((ordinal, index) => [`week${ordinal}`, (alternateOffWeeks & (1 << index)) !== 0])),
  };
}

/** The dialog's values as the API's days off. */
function weeklyOffRequest(values: Record<string, unknown>) {
  const alternateDay = values.alternateDay ? Number(values.alternateDay) : null;
  return {
    daysOfWeek: daysToMask(WEEKDAYS.map((day) => values[`day${day}`] === true)),
    alternateDay,
    alternateWeeks: alternateDay === null ? 0 : daysToMask(WEEK_ORDINALS.map((ordinal) => values[`week${ordinal}`] === true)),
  };
}

const HOLIDAY_FIELDS: FieldDef[] = [
  { name: "date", label: "Date", type: "date", required: true },
  { name: "name", label: "Name", required: true },
];

function UnitList({
  items,
  onEdit,
  onRemove,
}: {
  items: { id: string; label: string }[];
  onEdit?: (id: string) => void;
  onRemove?: (id: string) => void;
}) {
  if (items.length === 0) return <p className="text-sm text-muted-foreground">None yet.</p>;
  return (
    <ul className="divide-y text-sm">
      {items.map((item) => (
        <li key={item.id} className="flex items-center justify-between py-2">
          {item.label}
          <span className="flex gap-1">
            {onEdit && (
              <Button variant="ghost" size="sm" onClick={() => onEdit(item.id)}>
                Edit
              </Button>
            )}
            {onRemove && (
              <Button variant="ghost" size="sm" onClick={() => onRemove(item.id)}>
                Remove
              </Button>
            )}
          </span>
        </li>
      ))}
    </ul>
  );
}

export default function FacilityPage() {
  const confirm = useConfirm();
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const path = `/facilities/${id}`;
  const canManage = usePermission("FACILITY_MANAGE");
  const [dialog, setDialog] = useState<Dialog>(null);
  const [editingHoliday, setEditingHoliday] = useState<string | null>(null);
  const { run, message } = useAction();
  const refresh = useRefresh();
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<FacilityDetail>(path) });
  if (!data) return <QueryState error={error} />;

  const {
    facility,
    departments,
    productionLines,
    shifts,
    holidays,
    weeklyOffDays,
    alternateOffDay,
    alternateOffWeeks,
    stateHolidays,
  } = data;
  const setWorking = (holidayId: string, working: boolean) =>
    run(() => api.put(`${path}/state-holidays/${holidayId}`, { working }));
  const add = (button: string, target: Dialog) =>
    canManage && (
      <Button variant="outline" size="sm" onClick={() => setDialog(target)}>
        {button}
      </Button>
    );
  const remover = (segment: string) =>
    canManage ? (unitId: string) => run(() => api.delete(`${path}/${segment}/${unitId}`)) : undefined;

  return (
    <div className="space-y-6">
      <PageHeader title={facility.name} subtitle={facility.code}>
        {canManage && (
          <>
            <Button variant="outline" onClick={() => setDialog("edit")}>
              Edit
            </Button>
            <Button
              variant="destructive"
              onClick={() =>
                  confirm({
                    title: "Archive this facility?",
                    description: "It can no longer be chosen on new records. Its existing records are kept.",
                    confirmLabel: "Archive facility",
                    danger: true,
                  }).then((ok) => {
                    if (ok) void run(() => api.delete(path).then(() => router.push("/facilities")));
                  })
                }
            >
              Archive
            </Button>
          </>
        )}
      </PageHeader>
      <FormError message={message} />
      <Section title="Details">
        <Details
          items={[
            ["Address", facility.address],
            ["City", facility.city],
            ["State", facility.state],
            ["Postal code", facility.postalCode],
            ["GSTIN", facility.gstin],
          ]}
        />
      </Section>
      <div className="grid gap-6 lg:grid-cols-3">
        <Section title="Departments" action={add("Add department", "department")}>
          <UnitList
            items={departments.map((unit) => ({ id: unit.id, label: `${unit.code} – ${unit.name}` }))}
            onRemove={remover("departments")}
          />
        </Section>
        <Section title="Production lines" action={add("Add line", "line")}>
          <UnitList
            items={productionLines.map((unit) => ({ id: unit.id, label: `${unit.code} – ${unit.name}` }))}
            onRemove={remover("production-lines")}
          />
        </Section>
        <Section title="Shifts" action={add("Add shift", "shift")}>
          <UnitList
            items={shifts.map((shift) => ({
              id: shift.id,
              label: `${shift.name} (${shift.startsAt.slice(0, 5)}–${shift.endsAt.slice(0, 5)}, ${describeDays(shift.daysOfWeek)}${shift.breakMinutes > 0 ? `, ${shift.breakMinutes} min break` : ""})`,
            }))}
            onRemove={remover("shifts")}
          />
        </Section>
      </div>
      <Section
        title="Holidays"
        action={
          canManage && (
            <div className="flex gap-2">
              <Button variant="outline" size="sm" onClick={() => setDialog("weeklyOff")}>
                Weekly off
              </Button>
              <Button variant="outline" size="sm" onClick={() => setDialog("holiday")}>
                Add holiday
              </Button>
            </div>
          )
        }
      >
        <p className="mb-2 text-sm text-muted-foreground">
          No shift works on a holiday, its state&apos;s holidays, a weekly off or an alternate day off, so scheduling and machine availability give it no working time.
        </p>
        <p className="mb-2 text-sm">
          <span className="font-medium">Weekly off:</span> {weeklyOffDays === 0 ? "none" : describeDays(weeklyOffDays)}
        </p>
        <p className="mb-2 text-sm">
          <span className="font-medium">Alternate off:</span> {describeAlternateOff(alternateOffDay, alternateOffWeeks)}
        </p>
        <h3 className="mt-4 mb-1 text-sm font-semibold">This facility&apos;s own</h3>
        <UnitList
          items={holidays.map((holiday) => ({ id: holiday.id, label: `${holiday.date} – ${holiday.name}` }))}
          onEdit={canManage ? setEditingHoliday : undefined}
          onRemove={remover("holidays")}
        />
        <h3 className="mt-4 mb-1 text-sm font-semibold">
          From {facility.state ? `${facility.state} and ` : ""}All India
        </h3>
        <p className="mb-2 text-sm text-muted-foreground">
          Kept under{" "}
          <Link href="/state-holidays" className="underline underline-offset-4">
            State holidays
          </Link>
          {facility.state
            ? ". Mark one as a working day if this facility works on it."
            : ". Set this facility's state to take its state's holidays too."}
        </p>
        {stateHolidays.length === 0 ? (
          <p className="text-sm text-muted-foreground">None yet.</p>
        ) : (
          <ul className="divide-y text-sm">
            {stateHolidays.map((holiday) => (
              <li key={holiday.id} className="flex items-center justify-between gap-4 py-2">
                <span className={holiday.working ? "text-muted-foreground line-through" : undefined}>
                  {holiday.date} – {holiday.name}
                  <span className="text-muted-foreground"> · {holiday.regionName}</span>
                </span>
                <span className="flex items-center gap-2">
                  {holiday.working && <span className="text-xs text-muted-foreground">Working day</span>}
                  {canManage && (
                    <Button variant="ghost" size="sm" onClick={() => setWorking(holiday.id, !holiday.working)}>
                      {holiday.working ? "Close this day" : "Work this day"}
                    </Button>
                  )}
                </span>
              </li>
            ))}
          </ul>
        )}
      </Section>
      {dialog === "edit" && (
        <RecordDialog
          title="Edit facility"
          fields={FACILITY_FIELDS}
          record={facility}
          onClose={() => setDialog(null)}
          onSubmit={(values) => api.put(path, { ...values, version: facility.version }).then(refresh)}
        />
      )}
      {(dialog === "department" || dialog === "line") && (
        <RecordDialog
          title={dialog === "line" ? "Add production line" : "Add department"}
          fields={UNIT_FIELDS}
          onClose={() => setDialog(null)}
          onSubmit={(values) =>
            api
              .post(`${path}/${dialog === "line" ? "production-lines" : "departments"}`, values)
              .then(refresh)
          }
        />
      )}
      {dialog === "holiday" && (
        <RecordDialog
          title="Add holiday"
          fields={HOLIDAY_FIELDS}
          onClose={() => setDialog(null)}
          onSubmit={(values) => api.post(`${path}/holidays`, values).then(refresh)}
        />
      )}
      {editingHoliday !== null && (
        <RecordDialog
          title="Edit holiday"
          fields={HOLIDAY_FIELDS}
          record={holidays.find((holiday) => holiday.id === editingHoliday)}
          onClose={() => setEditingHoliday(null)}
          onSubmit={(values) => api.put(`${path}/holidays/${editingHoliday}`, values).then(refresh)}
        />
      )}
      {dialog === "weeklyOff" && (
        <RecordDialog
          title="Weekly off"
          fields={WEEKLY_OFF_FIELDS}
          record={weeklyOffRecord(weeklyOffDays, alternateOffDay, alternateOffWeeks)}
          onClose={() => setDialog(null)}
          onSubmit={(values) => api.put(`${path}/weekly-off`, weeklyOffRequest(values)).then(refresh)}
        />
      )}
      {dialog === "shift" && (
        <RecordDialog
          title="Add shift"
          fields={SHIFT_FIELDS}
          record={SHIFT_DEFAULTS}
          onClose={() => setDialog(null)}
          onSubmit={(values) => api.post(`${path}/shifts`, shiftRequest(values)).then(refresh)}
        />
      )}
    </div>
  );
}
