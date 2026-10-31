"use client";

import { useState } from "react";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { presetPeriod, PERIODS, type PeriodName } from "@/lib/platform-period";

/** A named period, or chosen dates. */
export function usePeriod() {
  const [preset, setPreset] = useState<PeriodName | "CUSTOM">("LAST_30");
  const [custom, setCustom] = useState(() => presetPeriod("LAST_30"));
  const period = preset === "CUSTOM" ? custom : presetPeriod(preset);
  const controls = (
    <div className="flex flex-wrap items-end gap-3">
      <div className="space-y-1.5">
        <Label htmlFor="platform-period">Period</Label>
        <select
          id="platform-period"
          className="h-9 rounded-md border bg-background px-2 text-sm"
          value={preset}
          onChange={(event) => setPreset(event.target.value as PeriodName | "CUSTOM")}
        >
          {PERIODS.map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
          <option value="CUSTOM">Custom dates</option>
        </select>
      </div>
      {preset === "CUSTOM" && (
        <>
          <div className="space-y-1.5">
            <Label htmlFor="platform-from">From</Label>
            <Input
              id="platform-from"
              type="date"
              value={custom.from}
              onChange={(event) => setCustom({ ...custom, from: event.target.value })}
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="platform-to">To</Label>
            <Input
              id="platform-to"
              type="date"
              value={custom.to}
              onChange={(event) => setCustom({ ...custom, to: event.target.value })}
            />
          </div>
        </>
      )}
    </div>
  );
  return { query: new URLSearchParams(period).toString(), controls };
}
