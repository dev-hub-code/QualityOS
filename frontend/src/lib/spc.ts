/** Control charts and capability, as the API works them out from inspection results. */

export type ChartChoice = "AUTO" | "XBAR_R" | "I_MR";
export type ChartType = Exclude<ChartChoice, "AUTO">;
export type Signal = "BEYOND_LIMITS" | "RANGE_BEYOND_LIMITS" | "RUN_OF_8";

export interface SpcParameter {
  templateId: string;
  templateCode: string;
  templateName: string;
  parameterCode: string;
  parameterName: string | null;
  subgroups: number;
  lastSubmittedAt: string;
}

/** A subgroup on an X̄-R chart, or one reading on an I-MR chart, with its own limits. */
export interface SpcPoint {
  inspectionId: string;
  inspectionNumber: string;
  batchNumber: string;
  submittedAt: string;
  /** I-MR only. */
  sampleNumber?: number;
  n: number;
  value: number;
  lcl: number;
  ucl: number;
  /** The range, or moving range: empty for the first reading. */
  range: number | null;
  rangeCentre: number;
  rangeLcl: number;
  rangeUcl: number;
  signals: Signal[];
}

export interface SpcChart {
  templateId: string;
  templateCode: string;
  templateName: string;
  from: string;
  to: string;
  chartType: ChartType;
  parameter: {
    code: string;
    name: string | null;
    unit: string | null;
    lsl: number | null;
    usl: number | null;
    nominal: number | null;
    specChanged: boolean;
  };
  centre: number;
  sigma: number;
  capability: { cp: number | null; cpk: number | null };
  points: SpcPoint[];
  excluded: { singleReading: number; tooLarge: number };
  truncated: boolean;
  warnings: string[];
}

/** One point as a chart panel draws it: its value and the limits that apply to it. */
export interface PanelPoint {
  key: string;
  /** What the point is, such as an inspection number and batch. */
  label: string;
  inspectionId: string;
  submittedAt: string;
  value: number | null;
  centre: number;
  lower: number;
  upper: number;
  /** Beyond its limits, or in a run: drawn as a signal. */
  signal: boolean;
}

/** Enough digits to tell measurements apart: five significant figures, and never more than six decimals. */
export function formatMeasure(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return "—";
  if (value === 0) return "0";
  const digits = Math.min(6, Math.max(0, 4 - Math.floor(Math.log10(Math.abs(value)))));
  return value.toFixed(digits).replace(/(\.\d*?)0+$/, "$1").replace(/\.$/, "");
}

/** An index to two decimals, as Cp and Cpk are read. */
export function formatIndex(value: number | null): string {
  return value === null || !Number.isFinite(value) ? "—" : value.toFixed(2);
}

/** The value axis: everything drawn, with a little room above and below. */
export function valueDomain(values: (number | null | undefined)[]): [number, number] {
  const finite = values.filter((value): value is number => typeof value === "number" && Number.isFinite(value));
  if (finite.length === 0) return [0, 1];
  const low = Math.min(...finite);
  const high = Math.max(...finite);
  if (low === high) {
    const pad = Math.abs(low) * 0.05 || 1;
    return [low - pad, high + pad];
  }
  const pad = (high - low) * 0.08;
  return [low - pad, high + pad];
}

/** Evenly spaced ticks on round steps, about {@code count} of them. */
export function ticks([low, high]: [number, number], count = 5): number[] {
  const rough = (high - low) / count;
  const magnitude = 10 ** Math.floor(Math.log10(rough));
  const step = [1, 2, 2.5, 5, 10].map((factor) => factor * magnitude).find((candidate) => candidate >= rough) ?? rough;
  const first = Math.ceil(low / step) * step;
  const found: number[] = [];
  for (let tick = first; tick <= high + step * 1e-9; tick += step) found.push(Number(tick.toPrecision(12)));
  return found;
}

/** A linear scale from a domain onto a pixel range, which may run backwards (as a y axis does). */
export function linear([d0, d1]: [number, number], [r0, r1]: [number, number]) {
  return (value: number) => r0 + ((value - d0) / (d1 - d0)) * (r1 - r0);
}

/**
 * A limit that may differ from point to point, drawn as steps: level across each point's band, changing halfway
 * between neighbours. With equal limits it is one straight line.
 */
export function stepPath(levels: number[], x: (index: number) => number, y: (value: number) => number,
  [left, right]: [number, number]): string {
  if (levels.length === 0) return "";
  const parts: string[] = [`M${left},${y(levels[0])}`];
  for (let i = 1; i < levels.length; i++) {
    const edge = (x(i - 1) + x(i)) / 2;
    parts.push(`H${edge}`);
    if (levels[i] !== levels[i - 1]) parts.push(`V${y(levels[i])}`);
  }
  parts.push(`H${right}`);
  return parts.join(" ");
}

export interface SignalLine {
  key: string;
  inspectionId: string;
  inspectionNumber: string;
  batchNumber: string;
  text: string;
}

/** Each signal in words, one line a point: "mean above the upper control limit", and so on. */
export function describeSignals(chart: SpcChart): SignalLine[] {
  const subgroups = chart.chartType === "XBAR_R";
  const value = subgroups ? "mean" : "reading";
  const range = subgroups ? "range" : "moving range";
  return chart.points.flatMap((point, index) => {
    if (point.signals.length === 0) return [];
    const centre = chart.centre;
    const parts = point.signals.map((signal) => {
      if (signal === "BEYOND_LIMITS") {
        return `${value} ${point.value > point.ucl ? "above the upper" : "below the lower"} control limit`;
      }
      if (signal === "RANGE_BEYOND_LIMITS") {
        return `${range} ${(point.range ?? 0) > point.rangeUcl ? "above the upper" : "below the lower"} control limit`;
      }
      return `part of a run of 8 or more ${point.value > centre ? "above" : "below"} the centre line`;
    });
    const sample = point.sampleNumber !== undefined ? `, sample ${point.sampleNumber}` : "";
    return [{
      key: `${point.inspectionId}-${index}`,
      inspectionId: point.inspectionId,
      inspectionNumber: `${point.inspectionNumber}${sample}`,
      batchNumber: point.batchNumber,
      text: parts.join("; "),
    }];
  });
}
