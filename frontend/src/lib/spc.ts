/** Statistical process control, as the API works it out from inspection measurements. */

export type ChartType = "XBAR_R" | "I_MR";

export interface Characteristic {
  templateId: string;
  templateCode: string;
  templateName: string;
  inspectionType: string;
  parameterCode: string;
  parameterName: string | null;
  measurements: number;
  lastMeasuredAt: string;
}

/** A centre line and its control limits; no lower limit on a moving-range chart. */
export interface Limits {
  centre: number;
  upper: number;
  lower: number | null;
}

/** A subgroup's mean and range, or one measurement and its moving range (null for the first). */
export interface SpcPoint {
  inspectionId: string;
  inspectionNumber: string;
  batchNumber: string;
  measuredAt: string;
  samples: number;
  value: number;
  range: number | null;
  outOfControl: boolean;
  rangeOutOfControl: boolean;
}

export interface Capability {
  measurements: number;
  mean: number;
  sigmaWithin: number | null;
  sigmaOverall: number | null;
  cp: number | null;
  cpk: number | null;
  pp: number | null;
  ppk: number | null;
}

export interface SpcChart {
  templateId: string;
  templateCode: string;
  templateName: string;
  parameterCode: string;
  parameterName: string | null;
  unit: string | null;
  chartType: ChartType;
  from: string;
  to: string;
  subgroupSize: number | null;
  excludedSubgroups: number;
  lowerSpec: number | null;
  upperSpec: number | null;
  nominal: number | null;
  specChanged: boolean;
  valueLimits: Limits;
  rangeLimits: Limits;
  points: SpcPoint[];
  capability: Capability;
  preliminary: boolean;
  truncated: boolean;
}

/** The usual reading of Cpk: 1.33 and over is capable, from 1.00 marginal, below that not capable. */
export function capabilityVerdict(cpk: number | null): { status: "good" | "warning" | "critical"; label: string } | null {
  if (cpk === null) return null;
  if (cpk >= 1.33) return { status: "good", label: "Capable" };
  if (cpk >= 1) return { status: "warning", label: "Marginal" };
  return { status: "critical", label: "Not capable" };
}

/** Enough digits to tell measurements apart: five significant figures, and never more than six decimals. */
export function formatMeasure(value: number | null): string {
  if (value === null || !Number.isFinite(value)) return "—";
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
  let low = Math.min(...finite);
  let high = Math.max(...finite);
  if (low === high) {
    const pad = Math.abs(low) * 0.05 || 1;
    return [low - pad, high + pad];
  }
  const pad = (high - low) * 0.08;
  low -= pad;
  high += pad;
  return [low, high];
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
