"use client";

import { useState } from "react";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

const SELECT_CLASS = "h-9 w-full rounded-xl border border-input bg-white/70 px-2 text-sm disabled:opacity-60";
/** The City option that lets a town missing from the list be typed. */
export const OTHER_CITY = "__other__";

interface PlaceSelectProps {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  error?: string;
}

/**
 * A state picked from the list. A record saved before the list existed may name a state it does not hold: that name
 * stays chosen, marked, until another is picked.
 */
export function StateSelect({ id, label, value, onChange, error, states, note }: PlaceSelectProps & {
  states: string[];
  note?: string | null;
}) {
  // While the list loads, the saved state is shown as it is.
  const unlisted = value.trim() !== "" && !states.includes(value);
  const loading = states.length === 0;
  return (
    <div className="space-y-1.5">
      <Label htmlFor={id}>{label}</Label>
      <select id={id} className={SELECT_CLASS} value={value} onChange={(event) => onChange(event.target.value)}>
        <option value="">Select…</option>
        {unlisted && <option value={value}>{loading ? value : `${value} (not in the list)`}</option>}
        {states.map((state) => (
          <option key={state} value={state}>
            {state}
          </option>
        ))}
      </select>
      {note && <p className="text-sm text-amber-700">{note}</p>}
      {error && <p className="text-sm text-destructive">{error}</p>}
    </div>
  );
}

/**
 * A city picked from the chosen state's list, or typed under Other when the list lacks it. A state that is not in the
 * list has no cities, so its city is typed.
 */
export function CitySelect({ id, label, value, onChange, error, cities, state }: PlaceSelectProps & {
  /** Undefined while the state's cities are loading. */
  cities: string[] | undefined;
  state: "none" | "listed" | "unlisted";
}) {
  const [typing, setTyping] = useState(false);
  const listed = state === "listed" && cities !== undefined;
  const other = listed && (typing || (value.trim() !== "" && !cities.includes(value)));
  let prompt = "Select…";
  if (state === "none") prompt = "Choose a state first";
  else if (!listed) prompt = "Loading…";
  return (
    <div className="space-y-1.5">
      <Label htmlFor={id}>{label}</Label>
      {state === "unlisted" ? (
        <Input id={id} value={value} onChange={(event) => onChange(event.target.value)} />
      ) : (
        <>
          <select
            id={id}
            className={SELECT_CLASS}
            disabled={!listed}
            value={other ? OTHER_CITY : value}
            onChange={(event) => {
              const chosen = event.target.value;
              setTyping(chosen === OTHER_CITY);
              onChange(chosen === OTHER_CITY ? "" : chosen);
            }}
          >
            <option value="">{prompt}</option>
            {listed && cities.map((city) => (
              <option key={city} value={city}>
                {city}
              </option>
            ))}
            {/* A saved city stays shown while the list loads. */}
            {!listed && value.trim() !== "" && <option value={value}>{value}</option>}
            {listed && <option value={OTHER_CITY}>Other…</option>}
          </select>
          {other && (
            <Input
              aria-label={`${label}, not in the list`}
              placeholder="Type the city"
              autoFocus={typing}
              value={value}
              onChange={(event) => onChange(event.target.value)}
            />
          )}
        </>
      )}
      {error && <p className="text-sm text-destructive">{error}</p>}
    </div>
  );
}
