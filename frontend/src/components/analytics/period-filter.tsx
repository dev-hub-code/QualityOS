"use client";

import { useFacilities } from "@/components/records/parts";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

export interface Period {
  from: string;
  to: string;
  facilityId: string;
}

export const NO_PERIOD: Period = { from: "", to: "", facilityId: "" };

export function periodQuery(period: Period): string {
  return new URLSearchParams(Object.entries(period).filter(([, value]) => value)).toString();
}

export function PeriodFilter({ value, onChange }: { value: Period; onChange: (next: Period) => void }) {
  const facilities = useFacilities();
  return (
    <div className="flex flex-wrap items-end gap-3">
      <div className="space-y-1.5">
        <Label htmlFor="period-from">From</Label>
        <Input id="period-from" type="date" value={value.from} onChange={(event) => onChange({ ...value, from: event.target.value })} />
      </div>
      <div className="space-y-1.5">
        <Label htmlFor="period-to">To</Label>
        <Input id="period-to" type="date" value={value.to} onChange={(event) => onChange({ ...value, to: event.target.value })} />
      </div>
      <div className="space-y-1.5">
        <Label htmlFor="period-facility">Facility</Label>
        <select
          id="period-facility"
          className="h-9 rounded-md border bg-background px-2 text-sm"
          value={value.facilityId}
          onChange={(event) => onChange({ ...value, facilityId: event.target.value })}
        >
          <option value="">All facilities</option>
          {facilities.map((facility) => (
            <option key={facility.value} value={facility.value}>
              {facility.label}
            </option>
          ))}
        </select>
      </div>
    </div>
  );
}
