import Link from "next/link";
import { describeSignals, formatIndex, formatMeasure, type SpcChart } from "@/lib/spc";

function Tile({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-xl border px-3 py-2">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="text-lg font-semibold tabular-nums">{value}</div>
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
    chart.truncated &&
      (chart.chartType === "XBAR_R"
        ? "Only the latest 500 inspections are charted. Choose a shorter period to see earlier ones."
        : "Only the latest 1,000 readings are charted. Choose a shorter period to see earlier ones."),
  ].filter((note): note is string => Boolean(note));
  return (
    <div className="space-y-3">
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-5">
        <Tile label="Cp" value={formatIndex(chart.capability.cp)} />
        <Tile label="Cpk" value={formatIndex(chart.capability.cpk)} />
        <Tile label={chart.chartType === "XBAR_R" ? "Centre (X̄)" : "Centre (x̄)"} value={formatMeasure(chart.centre)} />
        <Tile label="σ within" value={formatMeasure(chart.sigma)} />
        <Tile label="Spec LSL to USL" value={`${specText(chart.parameter)}${chart.parameter.lsl !== null || chart.parameter.usl !== null ? unit : ""}`} />
      </div>
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
