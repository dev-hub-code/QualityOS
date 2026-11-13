"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useState } from "react";
import { RescheduleDialog } from "@/components/manufacturing/reschedule-dialog";
import { PageHeader, QueryState, Section, StatusBadge, useFacilities } from "@/components/records/parts";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { formatMinutes, loadShare, localDate, PRIORITY_LABELS } from "@/lib/manufacturing";
import type { DayLoad, ScheduledOrder, SchedulingBoard } from "@/lib/types";

const SELECT_CLASS = "h-9 rounded-xl border border-input bg-white/70 px-2 text-sm";

function cellClass(day: DayLoad) {
  const share = loadShare(day);
  if (day.overloaded) return "bg-red-50 text-red-900 ring-1 ring-inset ring-red-200";
  if (share === null) return "text-muted-foreground";
  if (share >= 0.85) return "bg-amber-50 text-amber-900";
  return "";
}

const weekday = (date: string) => new Date(`${date}T00:00`).toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short" });

/** Load against capacity at each work centre of a facility, day by day; conflicts and warnings, never a fix. */
export default function SchedulingPage() {
  const canManage = usePermission("WORK_ORDER_MANAGE");
  const facilities = useFacilities();
  const [chosen, setChosen] = useState("");
  const facilityId = chosen || facilities[0]?.value || "";
  const [from, setFrom] = useState(localDate());
  const [to, setTo] = useState(localDate(13));
  const [moving, setMoving] = useState<ScheduledOrder | null>(null);
  const [moved, setMoved] = useState<{ number: string; warnings: string[] } | null>(null);
  const path = `/manufacturing/scheduling?facilityId=${facilityId}&from=${from}&to=${to}`;
  const { data, error } = useQuery({
    queryKey: ["/manufacturing/scheduling", facilityId, from, to],
    queryFn: () => api.get<SchedulingBoard>(path),
    enabled: Boolean(facilityId && from && to),
  });

  return (
    <div className="space-y-6">
      <PageHeader
        title="Scheduling"
        subtitle="Planned work against each work centre's capacity: shift hours × machines, less planned maintenance."
      />
      <div className="flex flex-wrap items-end gap-3 text-sm">
        <div className="space-y-1">
          <Label htmlFor="schedule-facility">Facility</Label>
          <select id="schedule-facility" className={SELECT_CLASS} value={facilityId} onChange={(event) => setChosen(event.target.value)}>
            {facilities.map((facility) => (
              <option key={facility.value} value={facility.value}>
                {facility.label}
              </option>
            ))}
          </select>
        </div>
        <div className="space-y-1">
          <Label htmlFor="schedule-from">From</Label>
          <Input id="schedule-from" type="date" value={from} onChange={(event) => setFrom(event.target.value)} />
        </div>
        <div className="space-y-1">
          <Label htmlFor="schedule-to">To</Label>
          <Input id="schedule-to" type="date" value={to} onChange={(event) => setTo(event.target.value)} />
        </div>
      </div>
      {moved && (
        <div
          role="status"
          className={`rounded-xl px-3 py-2 text-sm ring-1 ${moved.warnings.length ? "bg-amber-50 text-amber-900 ring-amber-200" : "bg-emerald-50 text-emerald-900 ring-emerald-200"}`}
        >
          <p className="font-medium">
            {moved.number} rescheduled{moved.warnings.length ? ", with warnings:" : ". It fits."}
          </p>
          {moved.warnings.length > 0 && (
            <ul className="mt-1 list-disc pl-5">
              {moved.warnings.map((warning) => (
                <li key={warning}>{warning}</li>
              ))}
            </ul>
          )}
        </div>
      )}
      {!facilityId ? (
        <p className="text-sm text-muted-foreground">Add a facility first.</p>
      ) : !data ? (
        <QueryState error={error} />
      ) : (
        <>
          {!data.shiftsDefined && (
            <p className="rounded-xl bg-amber-50 px-3 py-2 text-sm text-amber-900 ring-1 ring-amber-200">
              This facility has no shifts, so there is no capacity to plan against.{" "}
              <Link className="underline" href={`/facilities/${data.facilityId}`}>
                Add shifts to the facility
              </Link>
              .
            </p>
          )}
          <Section title={`Load against capacity (days in ${data.timezone})`}>
            {data.workCenters.length === 0 ? (
              <p className="text-sm text-muted-foreground">This facility has no work centres.</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="min-w-full text-xs">
                  <thead className="text-left text-muted-foreground">
                    <tr>
                      <th className="sticky left-0 bg-card py-1 pr-3 font-medium">Work centre</th>
                      {data.workCenters[0].days.map((day) => (
                        <th key={day.date} className="px-1 py-1 font-medium whitespace-nowrap">
                          {weekday(day.date)}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody className="divide-y">
                    {data.workCenters.map((centre) => (
                      <tr key={centre.id}>
                        <td className="sticky left-0 bg-card py-1.5 pr-3 whitespace-nowrap">
                          <span className="font-medium">{centre.code}</span>{" "}
                          <span className="text-muted-foreground">× {centre.units}</span>
                        </td>
                        {centre.days.map((day) => (
                          <td
                            key={day.date}
                            className={`px-1 py-1.5 whitespace-nowrap ${cellClass(day)}`}
                            title={`Capacity ${formatMinutes(day.capacityMinutes)}, planned maintenance ${formatMinutes(day.plannedDowntimeMinutes)}`}
                          >
                            {formatMinutes(day.loadMinutes)} / {formatMinutes(day.capacityMinutes)}
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Section>
          {data.conflicts.length > 0 && (
            <Section title="Conflicts">
              <ul className="divide-y text-sm">
                {data.conflicts.map((conflict) => (
                  <li key={`${conflict.workCenterId}-${conflict.date}`} className="py-2 text-red-800">
                    {conflict.workCenterCode} on {weekday(conflict.date)}: {formatMinutes(conflict.loadMinutes)} planned,{" "}
                    {formatMinutes(conflict.capacityMinutes)} available ({conflict.workOrderNumbers.join(", ")})
                  </li>
                ))}
              </ul>
            </Section>
          )}
          <Section title="Work orders">
            {data.orders.length === 0 ? (
              <p className="text-sm text-muted-foreground">No approved or running work orders in these dates.</p>
            ) : (
              <ul className="divide-y text-sm">
                {data.orders.map((order) => (
                  <li key={order.id} className="space-y-1 py-2">
                    <div className="flex flex-wrap items-center justify-between gap-3">
                      <span className="flex flex-wrap items-center gap-2">
                        <Link className="font-medium text-primary hover:underline" href={`/manufacturing/work-orders/${order.id}`}>
                          {order.workOrderNumber}
                        </Link>
                        <StatusBadge status={order.status} />
                        {order.productCode} · {PRIORITY_LABELS[order.priority]} · {order.plannedStart} to {order.plannedEnd} ·{" "}
                        {formatMinutes(order.remainingMinutes)} left
                      </span>
                      {canManage && (
                        <Button size="sm" variant="outline" onClick={() => setMoving(order)}>
                          Reschedule
                        </Button>
                      )}
                    </div>
                    {order.warnings.length > 0 && (
                      <ul className="list-disc pl-5 text-amber-800">
                        {order.warnings.map((warning) => (
                          <li key={warning}>{warning}</li>
                        ))}
                      </ul>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </Section>
        </>
      )}
      {moving && (
        <RescheduleDialog
          order={moving}
          onClose={() => setMoving(null)}
          onDone={(warnings) => setMoved({ number: moving.workOrderNumber, warnings })}
        />
      )}
    </div>
  );
}
