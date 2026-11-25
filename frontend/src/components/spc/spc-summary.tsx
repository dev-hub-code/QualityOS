import Link from "next/link";
import { coverage, describeSignals, formatIndex, formatMeasure, type SpcChart } from "@/lib/spc";

function Tile({ label, value, preliminary = false }: { label: string; value: string; preliminary?: boolean }) {
  return (
    <div className={`rounded-xl px-3 py-2 ${preliminary ? "border border-dashed" : "border"}`}>
      <div className="flex items-baseline justify-between gap-2 text-xs text-muted-foreground">
        {label}
        {preliminary && <span className="rounded border px-1 text-[10px] uppercase tracking-wide">Preliminary</span>}
      </div>
      <div className={`text-lg font-semibold tabular-nums ${preliminary ? "text-muted-foreground" : ""}`}>{value}</div>
    </div>
  );
}

function specText({ lsl, usl }: SpcChart["parameter"]): string {
  if (lsl !== null && usl !== null) return `${formatMeasure(lsl)} to ${formatMeasure(usl)}`;
  if (usl !== null) return `at most ${formatMeasure(usl)}`;
  if (lsl !== null) return `at least ${formatMeasure(lsl)}`;
  return "None";
}

/** Cp, Cpk, the centre, σ within and the specification, with the chart's warnings beneath. */
export function CapabilitySummary({ chart }: { chart: SpcChart }) {
  const unit = chart.parameter.unit ? ` ${chart.parameter.unit}` : "";
  const notes = [
    ...chart.warnings,
    chart.excluded.singleReading > 0 &&
      `${chart.excluded.singleReading} ${chart.excluded.singleReading === 1 ? "inspection" : "inspections"} with a single reading left out of the X̄-R chart.`,
    chart.excluded.tooLarge > 0 &&
      `${chart.excluded.tooLarge} ${chart.excluded.tooLarge === 1 ? "inspection" : "inspections"} with more than 25 readings left out of the X̄-R chart.`,
    chart.truncated && "Choose a shorter period to chart earlier readings.",
  ].filter((note): note is string => Boolean(note));
  const preliminary = chart.preliminary && chart.capability.cpk !== null;
  return (
    <div className="space-y-3">
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-5">
        <Tile label="Cp" value={formatIndex(chart.capability.cp)} preliminary={preliminary} />
        <Tile label="Cpk" value={formatIndex(chart.capability.cpk)} preliminary={preliminary} />
        <Tile label={chart.chartType === "XBAR_R" ? "Centre (X̄)" : "Centre (x̄)"} value={formatMeasure(chart.centre)} />
        <Tile label="σ within" value={formatMeasure(chart.sigma)} />
        <Tile label={`Spec LSL to USL · v${chart.parameter.specVersion}`} value={`${specText(chart.parameter)}${chart.parameter.lsl !== null || chart.parameter.usl !== null ? unit : ""}`} />
      </div>
      <p className="text-sm text-muted-foreground">{coverage(chart)}</p>
      {notes.length > 0 && (
        <ul role="list" aria-label="Warnings" className="list-disc space-y-0.5 pl-5 text-sm text-muted-foreground">
          {notes.map((note) => (
            <li key={note}>{note}</li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** What the figures assume, so they are read as what they are. */
export function SpcAssumptions({ chart }: { chart: SpcChart }) {
  const subgroups = chart.chartType === "XBAR_R";
  return (
    <details className="text-sm">
      <summary className="cursor-pointer text-muted-foreground">About these figures</summary>
      <ul className="mt-2 list-disc space-y-1 pl-5 text-muted-foreground">
        <li>
          Control limits show how the process behaves, worked out from the readings charted here. Specification limits
          are what the design requires, from template version {chart.parameter.specVersion}. A point outside the control
          limits can still be within specification, and the other way round.
        </li>
        <li>
          {subgroups
            ? "σ within comes from the ranges inside each inspection (R̄/d₂), pooled across inspections of different sizes."
            : "σ within comes from the moving range between consecutive readings in time order (MR̄/1.128). Consecutive readings can belong to different inspections, so lot-to-lot changes count as variation."}
        </li>
        <li>
          Cp and Cpk assume a stable process, with no signals, and readings that are roughly normal. This page does not
          test normality, and more points alone do not make a process stable.
        </li>
        <li>
          Limits are worked out afresh from the period chosen (Phase I) and are not stored. With fewer than 25 points
          they are preliminary.
        </li>
        <li>
          Capability is judged against the latest specification. If it changed during the period, earlier readings
          were made to other limits.
        </li>
      </ul>
    </details>
  );
}

/** Every signal, one line a point, each linking to its inspection. */
export function SignalList({ chart }: { chart: SpcChart }) {
  const lines = describeSignals(chart);
  if (lines.length === 0) return <p className="text-sm text-muted-foreground">No signals: every point is in control.</p>;
  return (
    <ul className="divide-y text-sm" aria-label="Signals">
      {lines.map((line) => (
        <li key={line.key} className="flex flex-wrap gap-x-1 py-2">
          <Link href={`/inspections/${line.inspectionId}`} className="underline underline-offset-4">
            {line.inspectionNumber}
          </Link>
          <span className="text-muted-foreground">
            · batch {line.batchNumber} · {line.text}
          </span>
        </li>
      ))}
    </ul>
  );
}
