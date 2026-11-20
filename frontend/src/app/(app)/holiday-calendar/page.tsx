"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { PageHeader, QueryState, Section } from "@/components/records/parts";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";
import { describeAlternateOff, describeDays } from "@/lib/manufacturing";
import type { CalendarHoliday, HolidayCalendar } from "@/lib/types";

const PERIODS = [
  { days: 90, label: "Next 3 months" },
  { days: 183, label: "Next 6 months" },
  { days: 366, label: "Next 12 months" },
] as const;

/** "2026-11-20" as a local day, so that no time zone moves it. */
function localDay(date: string): Date {
  const [year, month, day] = date.split("-").map(Number);
  return new Date(year, month - 1, day);
}

function isoDay(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, "0");
  return `${date.getFullYear()}-${month}-${String(date.getDate()).padStart(2, "0")}`;
}

function howSoon(date: string, today: string): string {
  const days = Math.round((localDay(date).getTime() - localDay(today).getTime()) / 86_400_000);
  if (days === 0) return "Today";
  if (days === 1) return "Tomorrow";
  return `In ${days} days`;
}

/** Holidays in date order, under the month they fall in. */
function byMonth(holidays: CalendarHoliday[]): [string, CalendarHoliday[]][] {
  const months = new Map<string, CalendarHoliday[]>();
  for (const holiday of holidays) {
    const month = localDay(holiday.date).toLocaleDateString(undefined, { month: "long", year: "numeric" });
    months.set(month, [...(months.get(month) ?? []), holiday]);
  }
  return [...months];
}

export default function HolidayCalendarPage() {
  const [days, setDays] = useState<number>(PERIODS[0].days);
  const { data, error } = useQuery({
    queryKey: ["/holiday-calendar", days],
    queryFn: () => {
      const from = new Date();
      const to = new Date(from.getFullYear(), from.getMonth(), from.getDate() + days - 1);
      return api.get<HolidayCalendar>(`/holiday-calendar?from=${isoDay(from)}&to=${isoDay(to)}`);
    },
  });

  const periodButtons = PERIODS.map((period) => (
    <Button
      key={period.days}
      variant={days === period.days ? "default" : "outline"}
      size="sm"
      onClick={() => setDays(period.days)}
    >
      {period.label}
    </Button>
  ));

  if (!data) {
    return (
      <div className="space-y-6">
        <PageHeader title="Holiday calendar" />
        <QueryState error={error} />
      </div>
    );
  }

  const severalFacilities = data.facilities.length > 1;
  const regular = data.facilities.filter((facility) => facility.weeklyOffDays !== 0 || facility.alternateOffDay !== null);

  return (
    <div className="max-w-4xl space-y-6">
      <PageHeader title="Holiday calendar" subtitle="Days your facilities are closed">
        {periodButtons}
      </PageHeader>
      <Section title="Upcoming holidays">
        {data.holidays.length === 0 ? (
          <p className="text-sm text-muted-foreground">No holidays in this period.</p>
        ) : (
          <div className="space-y-5">
            {byMonth(data.holidays).map(([month, holidays]) => (
              <div key={month}>
                <h3 className="mb-1 text-sm font-semibold text-muted-foreground">{month}</h3>
                <ul className="divide-y text-sm">
                  {holidays.map((holiday) => (
                    <li
                      key={`${holiday.facilityId}-${holiday.date}`}
                      className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 py-2"
                    >
                      <span className="flex items-baseline gap-3">
                        <span className="w-28 shrink-0 font-medium">
                          {localDay(holiday.date).toLocaleDateString(undefined, {
                            weekday: "short",
                            day: "numeric",
                            month: "short",
                          })}
                        </span>
                        <span>
                          {holiday.name}
                          {severalFacilities && (
                            <span className="text-muted-foreground"> · {holiday.facilityName}</span>
                          )}
                        </span>
                      </span>
                      <span className="text-xs text-muted-foreground">{howSoon(holiday.date, data.from)}</span>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        )}
      </Section>
      <Section title="Regular days off">
        {regular.length === 0 ? (
          <p className="text-sm text-muted-foreground">No weekly or alternate days off are set.</p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-muted-foreground">
                {severalFacilities && <th className="py-2 pr-4 font-normal">Facility</th>}
                <th className="py-2 pr-4 font-normal">Every week</th>
                <th className="py-2 font-normal">Some weeks of the month</th>
              </tr>
            </thead>
            <tbody className="divide-y">
              {regular.map((facility) => (
                <tr key={facility.id}>
                  {severalFacilities && <td className="py-2 pr-4">{facility.name}</td>}
                  <td className="py-2 pr-4">
                    {facility.weeklyOffDays === 0 ? "—" : describeDays(facility.weeklyOffDays)}
                  </td>
                  <td className="py-2">
                    {facility.alternateOffDay === null
                      ? "—"
                      : describeAlternateOff(facility.alternateOffDay, facility.alternateOffWeeks)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Section>
    </div>
  );
}
