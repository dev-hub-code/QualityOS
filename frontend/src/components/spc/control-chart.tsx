"use client";

import Link from "next/link";
import { useState, type KeyboardEvent } from "react";
import { formatMeasure, linear, stepPath, ticks, valueDomain, type PanelPoint } from "@/lib/spc";

interface ControlChartProps {
  title: string;
  /** What a point's value is, such as "Mean" or "Moving range". */
  valueName: string;
  unit: string | null;
  points: PanelPoint[];
  /** Specification limits, drawn on the value panel only. */
  spec?: { lower: number | null; upper: number | null };
  /** Opens a point's inspection when it is clicked or chosen with Enter. */
  onOpen?: (point: PanelPoint) => void;
}

export const WIDTH = 760;
export const HEIGHT = 240;
export const MARGIN = { top: 14, right: 104, bottom: 30, left: 60 };
const PLOT_WIDTH = WIDTH - MARGIN.left - MARGIN.right;
const PLOT_HEIGHT = HEIGHT - MARGIN.top - MARGIN.bottom;
const LEFT = MARGIN.left;
const RIGHT = MARGIN.left + PLOT_WIDTH;

const day = (instant: string) => new Date(instant).toLocaleDateString(undefined, { day: "numeric", month: "short" });
const time = (instant: string) =>
  new Date(instant).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });

interface Line {
  label: string;
  levels: number[];
  dash?: string;
  strong?: boolean;
}

/**
 * A Shewhart control chart panel: the points in order, the centre line and control limits (stepping where a point's
 * sample size gives it limits of its own), and on the value panel the specification. Signals are marked in red, and
 * named in the tooltip and the table.
 */
export function ControlChart({ title, valueName, unit, points, spec, onOpen }: ControlChartProps) {
  const [active, setActive] = useState<number | null>(null);
  const lines: Line[] = points.length === 0 ? [] : [
    { label: "UCL", levels: points.map((point) => point.upper), dash: "6 4" },
    { label: "CL", levels: points.map((point) => point.centre) },
    { label: "LCL", levels: points.map((point) => point.lower), dash: "6 4" },
    ...(spec?.upper != null ? [{ label: "USL", levels: points.map(() => spec.upper as number), dash: "2 3", strong: true }] : []),
    ...(spec?.lower != null ? [{ label: "LSL", levels: points.map(() => spec.lower as number), dash: "2 3", strong: true }] : []),
  ];
  const domain = valueDomain([...points.map((point) => point.value), ...lines.flatMap((line) => line.levels)]);
  const y = linear(domain, [MARGIN.top + PLOT_HEIGHT, MARGIN.top]);
  const step = points.length > 1 ? PLOT_WIDTH / (points.length - 1) : 0;
  const x = (index: number) => LEFT + (points.length > 1 ? index * step : PLOT_WIDTH / 2);
  const drawn = points.map((point, index) => ({ point, index })).filter(({ point }) => point.value !== null);
  const path = drawn.map(({ point, index }, at) => `${at === 0 ? "M" : "L"}${x(index)},${y(point.value as number)}`).join(" ");
  const signals = points.filter((point) => point.signal).length;
  const shown = active !== null ? points[active] : null;
  const stepped = lines.some((line) => line.levels.some((level) => level !== line.levels[0]));
  const dateTicks = points.length <= 1 ? [0] : [0, Math.round((points.length - 1) / 2), points.length - 1];
  // Points all on one day are told apart by the time.
  const oneDay = points.length > 1 && day(points[0].submittedAt) === day(points[points.length - 1].submittedAt);
  const tickLabel = (instant: string) => (oneDay ? time(instant) : day(instant));
  const withUnit = (value: number | null) => `${formatMeasure(value)}${unit ? ` ${unit}` : ""}`;

  // Direct labels at the right, at the last point's levels, nudged apart so they never overlap.
  const labelled = lines
    .map((line) => ({ ...line, last: line.levels[line.levels.length - 1] }))
    .sort((a, b) => y(a.last) - y(b.last));
  const labelY: number[] = [];
  labelled.forEach((line, index) => {
    const wanted = y(line.last);
    labelY.push(index === 0 ? wanted : Math.max(wanted, labelY[index - 1] + 12));
  });

  function onKeyDown(event: KeyboardEvent) {
    if (points.length === 0) return;
    if (event.key === "ArrowRight" || event.key === "ArrowLeft") {
      event.preventDefault();
      const move = event.key === "ArrowRight" ? 1 : -1;
      setActive((current) => Math.min(points.length - 1, Math.max(0, (current ?? (move > 0 ? -1 : points.length)) + move)));
    } else if (event.key === "Enter" && shown && onOpen) {
      onOpen(shown);
    } else if (event.key === "Escape") {
      setActive(null);
    }
  }

  return (
    <figure className="viz-root space-y-2">
      <figcaption className="flex flex-wrap items-baseline justify-between gap-2">
        <span className="text-sm font-medium">{title}</span>
        {signals > 0 ? (
          <span className="flex items-center gap-1.5 text-xs text-foreground">
            <span aria-hidden className="size-2.5 rounded-full" style={{ background: "var(--viz-critical)" }} />
            {signals} {signals === 1 ? "signal" : "signals"}
          </span>
        ) : (
          <span className="text-xs text-muted-foreground">No signals</span>
        )}
      </figcaption>
      <div className="relative">
        <svg
          viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
          className="h-auto w-full touch-none rounded-md outline-none focus-visible:ring-2 focus-visible:ring-ring"
          role="img"
          aria-label={`${title}: ${points.length} points, ${signals} ${signals === 1 ? "signal" : "signals"}. Use the arrow keys to read each point, and Enter to open its inspection.`}
          tabIndex={0}
          onKeyDown={onKeyDown}
          onPointerLeave={() => setActive(null)}
          onBlur={() => setActive(null)}
        >
          {ticks(domain).map((tick) => (
            <g key={tick}>
              <line x1={LEFT} x2={RIGHT} y1={y(tick)} y2={y(tick)} stroke="var(--viz-grid)" strokeWidth={1} />
              <text x={LEFT - 8} y={y(tick)} dy="0.32em" textAnchor="end" className="fill-muted-foreground text-[11px] tabular-nums">
                {formatMeasure(tick)}
              </text>
            </g>
          ))}
          <line x1={LEFT} x2={RIGHT} y1={MARGIN.top + PLOT_HEIGHT} y2={MARGIN.top + PLOT_HEIGHT} stroke="var(--viz-axis)" strokeWidth={1} />
          {dateTicks.map((index) => (
            <text
              key={index}
              x={x(index)}
              y={HEIGHT - 8}
              textAnchor={points.length > 1 && index === 0 ? "start" : points.length > 1 && index === points.length - 1 ? "end" : "middle"}
              className="fill-muted-foreground text-[11px]"
            >
              {points[index] ? tickLabel(points[index].submittedAt) : ""}
            </text>
          ))}
          {lines.map((line) => (
            <path
              key={line.label}
              data-line={line.label}
              d={stepPath(line.levels, x, y, [LEFT, RIGHT])}
              fill="none"
              className={line.strong ? "stroke-foreground" : "stroke-muted-foreground"}
              strokeWidth={line.strong ? 1.25 : 1}
              strokeDasharray={line.dash}
            />
          ))}
          {labelled.map((line, index) => (
            <text
              key={line.label}
              x={RIGHT + 8}
              y={labelY[index]}
              dy="0.32em"
              className={`text-[11px] tabular-nums ${line.strong ? "fill-foreground" : "fill-muted-foreground"}`}
            >
              {line.label} {formatMeasure(line.last)}
            </text>
          ))}
          {shown && active !== null && (
            <line x1={x(active)} x2={x(active)} y1={MARGIN.top} y2={MARGIN.top + PLOT_HEIGHT} stroke="var(--viz-axis)" strokeWidth={1} />
          )}
          <path d={path} fill="none" stroke="var(--viz-1)" strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
          {drawn.map(({ point, index }) => (
            <circle
              key={point.key}
              data-signal={point.signal || undefined}
              cx={x(index)}
              cy={y(point.value as number)}
              r={point.signal ? 5.5 : active === index ? 5 : 4}
              fill={point.signal ? "var(--viz-critical)" : "var(--viz-1)"}
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
              className={onOpen ? "cursor-pointer" : undefined}
              onPointerEnter={() => setActive(index)}
              onPointerDown={() => setActive(index)}
              onClick={() => onOpen?.(point)}
            />
          ))}
        </svg>
        {shown && active !== null && (
          <div
            role="status"
            className="pointer-events-none absolute top-1 z-10 w-max max-w-60 -translate-x-1/2 rounded-lg border bg-popover px-2.5 py-1.5 text-xs shadow-md"
            style={{ left: `${(Math.min(Math.max(x(active), LEFT + 60), RIGHT - 40) / WIDTH) * 100}%` }}
          >
            <div className="flex items-center gap-1.5">
              <span aria-hidden className="h-0.5 w-3 rounded" style={{ background: shown.signal ? "var(--viz-critical)" : "var(--viz-1)" }} />
              <span className="font-semibold tabular-nums">{withUnit(shown.value)}</span>
              <span className="text-muted-foreground">{valueName}</span>
            </div>
            <div className="text-muted-foreground tabular-nums">
              limits {formatMeasure(shown.lower)} to {formatMeasure(shown.upper)}
            </div>
            <div className="text-muted-foreground">{shown.label}</div>
            <div className="text-muted-foreground">{new Date(shown.submittedAt).toLocaleString()}</div>
            {shown.signal && <div className="font-medium">Signal</div>}
            {onOpen && <div className="text-muted-foreground">Click to open the inspection</div>}
          </div>
        )}
      </div>
      {stepped && <p className="text-right text-xs text-muted-foreground">Limits step with each inspection&apos;s sample size.</p>}
      <details className="text-sm">
        <summary className="cursor-pointer text-muted-foreground">Show as a table</summary>
        <div className="max-h-72 overflow-auto">
          <table className="mt-1 w-full text-left">
            <thead>
              <tr className="text-muted-foreground">
                <th className="pr-3 font-medium">#</th>
                <th className="pr-3 font-medium">Inspection</th>
                <th className="pr-3 font-medium">Submitted</th>
                <th className="pr-3 font-medium">{valueName}</th>
                <th className="pr-3 font-medium">LCL</th>
                <th className="pr-3 font-medium">UCL</th>
                <th className="font-medium">Signal</th>
              </tr>
            </thead>
            <tbody>
              {points.map((point, index) => (
                <tr key={point.key}>
                  <td className="pr-3 tabular-nums">{index + 1}</td>
                  <td className="pr-3">
                    <Link href={`/inspections/${point.inspectionId}`} className="underline underline-offset-4">
                      {point.label}
                    </Link>
                  </td>
                  <td className="pr-3">{new Date(point.submittedAt).toLocaleString()}</td>
                  <td className="pr-3 tabular-nums">{withUnit(point.value)}</td>
                  <td className="pr-3 tabular-nums">{formatMeasure(point.lower)}</td>
                  <td className="pr-3 tabular-nums">{formatMeasure(point.upper)}</td>
                  <td>{point.value === null ? "—" : point.signal ? "Yes" : "No"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </figure>
  );
}
