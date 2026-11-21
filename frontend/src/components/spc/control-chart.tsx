"use client";

import { useState, type KeyboardEvent } from "react";
import { formatMeasure, ticks, valueDomain, type Limits } from "@/lib/spc";

export interface ChartPoint {
  key: string;
  /** What the point is, such as an inspection number and batch. */
  label: string;
  measuredAt: string;
  value: number | null;
  outOfControl: boolean;
}

interface ControlChartProps {
  title: string;
  /** What a point's value is, such as "Mean" or "Range". */
  valueName: string;
  unit: string | null;
  points: ChartPoint[];
  limits: Limits;
  /** Specification limits, drawn on the value chart only. */
  spec?: { lower: number | null; upper: number | null };
}

const WIDTH = 760;
const HEIGHT = 240;
const MARGIN = { top: 14, right: 96, bottom: 30, left: 60 };
const PLOT_WIDTH = WIDTH - MARGIN.left - MARGIN.right;
const PLOT_HEIGHT = HEIGHT - MARGIN.top - MARGIN.bottom;

const day = (instant: string) =>
  new Date(instant).toLocaleDateString(undefined, { day: "numeric", month: "short" });
const time = (instant: string) =>
  new Date(instant).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });

interface ReferenceLine {
  label: string;
  value: number;
  dash?: string;
  strong?: boolean;
}

/**
 * A Shewhart control chart: the points in time order, their centre line and control limits, and, on the value chart,
 * the specification. A point beyond the control limits is marked, and named in its tooltip and in the table.
 */
export function ControlChart({ title, valueName, unit, points, limits, spec }: ControlChartProps) {
  const [active, setActive] = useState<number | null>(null);
  const references: ReferenceLine[] = [
    { label: "UCL", value: limits.upper, dash: "6 4" },
    { label: "CL", value: limits.centre },
    ...(limits.lower !== null ? [{ label: "LCL", value: limits.lower, dash: "6 4" }] : []),
    ...(spec?.upper != null ? [{ label: "USL", value: spec.upper, dash: "2 3", strong: true }] : []),
    ...(spec?.lower != null ? [{ label: "LSL", value: spec.lower, dash: "2 3", strong: true }] : []),
  ];
  const domain = valueDomain([...points.map((point) => point.value), ...references.map((line) => line.value)]);
  const y = (value: number) => MARGIN.top + PLOT_HEIGHT - ((value - domain[0]) / (domain[1] - domain[0])) * PLOT_HEIGHT;
  const step = points.length > 1 ? PLOT_WIDTH / (points.length - 1) : 0;
  const x = (index: number) => MARGIN.left + (points.length > 1 ? index * step : PLOT_WIDTH / 2);
  const drawn = points.map((point, index) => ({ point, index })).filter(({ point }) => point.value !== null);
  const path = drawn.map(({ point, index }, at) => `${at === 0 ? "M" : "L"}${x(index)},${y(point.value as number)}`).join(" ");
  const outside = points.filter((point) => point.outOfControl).length;
  const shown = active !== null ? points[active] : null;
  const dateTicks = points.length <= 1 ? [0] : [0, Math.round((points.length - 1) / 2), points.length - 1];
  // Points all on one day are told apart by the time.
  const oneDay =
    points.length > 1 && day(points[0].measuredAt) === day(points[points.length - 1].measuredAt);
  const tickLabel = (instant: string) => (oneDay ? time(instant) : day(instant));
  const withUnit = (value: number | null) => `${formatMeasure(value)}${unit ? ` ${unit}` : ""}`;

  // Direct labels on the right, nudged apart so they never sit on one another.
  const labelled = [...references].sort((a, b) => y(a.value) - y(b.value));
  const labelY: number[] = [];
  labelled.forEach((line, index) => {
    const wanted = y(line.value);
    labelY.push(index === 0 ? wanted : Math.max(wanted, labelY[index - 1] + 12));
  });

  function onKeyDown(event: KeyboardEvent) {
    if (points.length === 0) return;
    if (event.key === "ArrowRight" || event.key === "ArrowLeft") {
      event.preventDefault();
      const move = event.key === "ArrowRight" ? 1 : -1;
      setActive((current) => Math.min(points.length - 1, Math.max(0, (current ?? (move > 0 ? -1 : points.length)) + move)));
    } else if (event.key === "Escape") {
      setActive(null);
    }
  }

  return (
    <figure className="viz-root space-y-2">
      <figcaption className="flex flex-wrap items-baseline justify-between gap-2">
        <span className="text-sm font-medium">{title}</span>
        {outside > 0 ? (
          <span className="flex items-center gap-1.5 text-xs text-foreground">
            <span aria-hidden className="size-2.5 rounded-full" style={{ background: "var(--viz-critical)" }} />
            {outside} {outside === 1 ? "point" : "points"} beyond the control limits
          </span>
        ) : (
          <span className="text-xs text-muted-foreground">All points within the control limits</span>
        )}
      </figcaption>
      <div className="relative">
        <svg
          viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
          className="h-auto w-full touch-none outline-none focus-visible:ring-2 focus-visible:ring-ring rounded-md"
          role="img"
          aria-label={`${title}: ${points.length} points, centre ${withUnit(limits.centre)}, ${outside} beyond the control limits. Use the arrow keys to read each point.`}
          tabIndex={0}
          onKeyDown={onKeyDown}
          onPointerLeave={() => setActive(null)}
          onBlur={() => setActive(null)}
        >
          {ticks(domain).map((tick) => (
            <g key={tick}>
              <line x1={MARGIN.left} x2={MARGIN.left + PLOT_WIDTH} y1={y(tick)} y2={y(tick)} stroke="var(--viz-grid)" strokeWidth={1} />
              <text x={MARGIN.left - 8} y={y(tick)} dy="0.32em" textAnchor="end" className="fill-muted-foreground text-[11px] tabular-nums">
                {formatMeasure(tick)}
              </text>
            </g>
          ))}
          <line
            x1={MARGIN.left}
            x2={MARGIN.left + PLOT_WIDTH}
            y1={MARGIN.top + PLOT_HEIGHT}
            y2={MARGIN.top + PLOT_HEIGHT}
            stroke="var(--viz-axis)"
            strokeWidth={1}
          />
          {dateTicks.map((index) => (
            <text
              key={index}
              x={x(index)}
              y={HEIGHT - 8}
              textAnchor={index === 0 && points.length > 1 ? "start" : index === points.length - 1 && points.length > 1 ? "end" : "middle"}
              className="fill-muted-foreground text-[11px]"
            >
              {points[index] ? tickLabel(points[index].measuredAt) : ""}
            </text>
          ))}
          {references.map((line) => (
            <line
              key={line.label}
              x1={MARGIN.left}
              x2={MARGIN.left + PLOT_WIDTH}
              y1={y(line.value)}
              y2={y(line.value)}
              className={line.strong ? "stroke-foreground" : "stroke-muted-foreground"}
              strokeWidth={line.strong ? 1.25 : 1}
              strokeDasharray={line.dash}
            />
          ))}
          {labelled.map((line, index) => (
            <text
              key={line.label}
              x={MARGIN.left + PLOT_WIDTH + 8}
              y={labelY[index]}
              dy="0.32em"
              className={`text-[11px] tabular-nums ${line.strong ? "fill-foreground" : "fill-muted-foreground"}`}
            >
              {line.label} {formatMeasure(line.value)}
            </text>
          ))}
          {shown && active !== null && (
            <line x1={x(active)} x2={x(active)} y1={MARGIN.top} y2={MARGIN.top + PLOT_HEIGHT} stroke="var(--viz-axis)" strokeWidth={1} />
          )}
          <path d={path} fill="none" stroke="var(--viz-1)" strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
          {drawn.map(({ point, index }) => (
            <circle
              key={point.key}
              cx={x(index)}
              cy={y(point.value as number)}
              r={point.outOfControl ? 5.5 : active === index ? 5 : 4}
              fill={point.outOfControl ? "var(--viz-critical)" : "var(--viz-1)"}
              stroke="var(--viz-surface)"
              strokeWidth={2}
            />
          ))}
          {/* Hit targets wider than the marks: one band a point, the plot's full height. */}
          {points.map((point, index) => (
            <rect
              key={point.key}
              x={x(index) - Math.max(step, 12) / 2}
              y={MARGIN.top}
              width={Math.max(step, 12)}
              height={PLOT_HEIGHT}
              fill="transparent"
              onPointerEnter={() => setActive(index)}
              onPointerDown={() => setActive(index)}
            />
          ))}
        </svg>
        {shown && active !== null && (
          <div
            role="status"
            className="pointer-events-none absolute top-1 z-10 w-max max-w-56 -translate-x-1/2 rounded-lg border bg-popover px-2.5 py-1.5 text-xs shadow-md"
            style={{ left: `${(Math.min(Math.max(x(active), MARGIN.left + 60), MARGIN.left + PLOT_WIDTH - 40) / WIDTH) * 100}%` }}
          >
            <div className="flex items-center gap-1.5">
              <span aria-hidden className="h-0.5 w-3 rounded" style={{ background: shown.outOfControl ? "var(--viz-critical)" : "var(--viz-1)" }} />
              <span className="font-semibold tabular-nums">{withUnit(shown.value)}</span>
              <span className="text-muted-foreground">{valueName}</span>
            </div>
            <div className="text-muted-foreground">{shown.label}</div>
            <div className="text-muted-foreground">{new Date(shown.measuredAt).toLocaleString()}</div>
            {shown.outOfControl && <div className="font-medium">Beyond the control limits</div>}
          </div>
        )}
      </div>
      <details className="text-sm">
        <summary className="cursor-pointer text-muted-foreground">Show as a table</summary>
        <div className="max-h-72 overflow-auto">
          <table className="mt-1 w-full text-left">
            <thead>
              <tr className="text-muted-foreground">
                <th className="pr-3 font-medium">#</th>
                <th className="pr-3 font-medium">Inspection</th>
                <th className="pr-3 font-medium">Measured</th>
                <th className="pr-3 font-medium">{valueName}</th>
                <th className="font-medium">Control</th>
              </tr>
            </thead>
            <tbody>
              {points.map((point, index) => (
                <tr key={point.key}>
                  <td className="pr-3 tabular-nums">{index + 1}</td>
                  <td className="pr-3">{point.label}</td>
                  <td className="pr-3">{new Date(point.measuredAt).toLocaleString()}</td>
                  <td className="pr-3 tabular-nums">{withUnit(point.value)}</td>
                  <td>{point.value === null ? "—" : point.outOfControl ? "Out of control" : "In control"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </figure>
  );
}
