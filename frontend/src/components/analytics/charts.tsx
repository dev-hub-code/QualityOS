"use client";

import type { ReactNode } from "react";

const EMPTY = "Nothing to show for this period.";

function ChartTable({ headings, rows }: { headings: string[]; rows: (string | number)[][] }) {
  return (
    <details className="mt-2 text-sm">
      <summary className="cursor-pointer text-muted-foreground">Show as a table</summary>
      <div className="overflow-x-auto">
        <table className="mt-1 w-full text-left">
          <thead>
            <tr>
              {headings.map((heading) => (
                <th key={heading} className="pr-3 font-medium">
                  {heading}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={String(row[0])}>
                {row.map((cell, index) => (
                  <td key={index} className="pr-3">
                    {cell}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </details>
  );
}

function Tip({ children }: { children: ReactNode }) {
  return (
    <span
      role="tooltip"
      className="pointer-events-none absolute bottom-full left-1/2 z-10 mb-1 hidden -translate-x-1/2 rounded-md border bg-popover px-2 py-1 text-xs whitespace-nowrap text-popover-foreground shadow group-hover:block group-focus:block"
    >
      {children}
    </span>
  );
}

export interface Series {
  key: string;
  label: string;
}

function monthName(month: string) {
  const [year, number] = month.split("-").map(Number);
  return new Date(year, number - 1).toLocaleString("en-IN", { month: "short" });
}

/**
 * Two series by month. Stacked when they are parts of a whole, with series 1 on the baseline so that it can be
 * compared across months. Side by side when their sum would mean nothing.
 */
export function MonthBars<T extends { month: string }>({
  data,
  series,
  grouped = false,
}: {
  data: T[];
  series: [Series, Series];
  grouped?: boolean;
}) {
  const value = (row: T, key: string) => Number(row[key as keyof T]);
  if (data.every((row) => series.every(({ key }) => value(row, key) === 0))) return <p className="text-sm text-muted-foreground">{EMPTY}</p>;
  const total = (row: T) =>
    grouped
      ? Math.max(value(row, series[0].key), value(row, series[1].key))
      : value(row, series[0].key) + value(row, series[1].key);
  const highest = Math.max(1, ...data.map(total));
  return (
    <div className="viz-root">
      <ul className="mb-2 flex flex-wrap gap-3 text-xs text-muted-foreground" aria-label="Legend">
        {series.map(({ key, label }, index) => (
          <li key={key} className="flex items-center gap-1">
            <span className="size-2.5 rounded-sm" style={{ background: `var(--viz-${index + 1})` }} />
            {label}
          </li>
        ))}
      </ul>
      <div className="flex h-40 items-end gap-2 border-b pt-6">
        {data.map((row) => {
          const [low, high] = series.map(({ key }) => value(row, key));
          return (
            <div
              key={row.month}
              tabIndex={0}
              aria-label={`${row.month}: ${low} ${series[0].label}, ${high} ${series[1].label}`}
              className={`group relative flex h-full max-w-12 min-w-0 flex-1 gap-0.5 outline-none focus-visible:ring-2 ${
                grouped ? "items-end" : "flex-col justify-end"
              }`}
            >
              <Tip>
                {row.month}: {low} {series[0].label.toLowerCase()}, {high} {series[1].label.toLowerCase()}
              </Tip>
              {grouped ? (
                <>
                  <div className="flex-1 rounded-t" style={{ height: `${(low / highest) * 100}%`, background: "var(--viz-1)" }} />
                  <div className="flex-1 rounded-t" style={{ height: `${(high / highest) * 100}%`, background: "var(--viz-2)" }} />
                </>
              ) : (
                <>
                  {high > 0 && (
                    <div className="rounded-t" style={{ height: `${(high / highest) * 100}%`, background: "var(--viz-2)" }} />
                  )}
                  {low > 0 && (
                    <div
                      className={high > 0 ? "" : "rounded-t"}
                      style={{ height: `${(low / highest) * 100}%`, background: "var(--viz-1)" }}
                    />
                  )}
                </>
              )}
            </div>
          );
        })}
      </div>
      <div className="mt-1 flex gap-2 text-xs text-muted-foreground">
        {data.map((row) => (
          <span key={row.month} className="max-w-12 min-w-0 flex-1 truncate text-center">
            {monthName(row.month)}
          </span>
        ))}
      </div>
      <ChartTable
        headings={["Month", series[0].label, series[1].label]}
        rows={data.map((row) => [row.month, value(row, series[0].key), value(row, series[1].key)])}
      />
    </div>
  );
}

export interface Rank {
  label: string;
  value: number;
  /** The figure printed beside the bar. */
  display: string;
  note?: string;
}

/** Horizontal bars, in the order given. A cumulative share is a column beside them, never a second axis. */
export function RankBars({
  rows,
  max,
  headings,
}: {
  rows: Rank[];
  /** Fixed when the bars are percentages, so that a lone 5% does not fill the width. */
  max?: number;
  headings: [string, string];
}) {
  if (rows.length === 0) return <p className="text-sm text-muted-foreground">{EMPTY}</p>;
  const scale = max ?? Math.max(1, ...rows.map((row) => row.value));
  return (
    <div className="viz-root">
      <ul className="space-y-1.5 text-sm">
        {rows.map((row) => (
          <li key={row.label} className="grid grid-cols-[minmax(0,2fr)_minmax(0,3fr)_auto] items-center gap-2">
            <span className="truncate" title={row.label}>
              {row.label}
            </span>
            <div
              tabIndex={0}
              aria-label={`${row.label}: ${row.display}`}
              className="group relative h-3 rounded-r bg-muted outline-none focus-visible:ring-2"
            >
              <Tip>
                {row.label}: {row.display}
                {row.note && `, ${row.note}`}
              </Tip>
              <div
                className="h-full rounded-r"
                style={{ width: `${(row.value / scale) * 100}%`, background: "var(--viz-1)" }}
              />
            </div>
            <span className="text-right text-xs text-muted-foreground tabular-nums">{row.display}</span>
          </li>
        ))}
      </ul>
      <ChartTable
        headings={[...headings, ...(rows.some((row) => row.note) ? ["Detail"] : [])]}
        rows={rows.map((row) => [row.label, row.display, ...(row.note ? [row.note] : [])])}
      />
    </div>
  );
}
