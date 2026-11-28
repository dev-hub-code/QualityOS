import type { Unit } from "@/lib/types";

/** "1 MG = 0.001 GM": a unit in terms of the base unit of its kind, or "–" for one that never converts. */
export function equivalence(unit: Unit, units: Unit[]): string {
  if (unit.toBase === null) return "–";
  const base = units.find((other) => other.dimension === unit.dimension && Number(other.toBase) === 1);
  if (!base || base.id === unit.id) return "Base unit";
  return `1 ${unit.code} = ${Number(unit.toBase).toLocaleString("en-IN", { maximumFractionDigits: 12 })} ${base.code}`;
}

/** Sends only what was filled in: a unit with no amount never converts. */
export function unitRequest(values: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(Object.entries(values).filter(([, value]) => value !== "" && value !== null));
}
