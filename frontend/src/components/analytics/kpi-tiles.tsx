import type { Kpis } from "@/lib/types";

export const rate = (value: number | null) => (value === null ? "No data" : `${value}%`);

function hours(value: number | null) {
  if (value === null) return "No data";
  return value < 48 ? `${value} h` : `${(value / 24).toFixed(1)} days`;
}

const KEY = (kpis: Kpis): [string, string | number][] => [
  ["Inspections decided", kpis.totalInspections],
  ["Pass rate", rate(kpis.inspectionPassRate)],
  ["Open NCRs", kpis.openNcrs],
  ["Overdue CAPAs", kpis.overdueCapas],
];

const REST = (kpis: Kpis): [string, string | number][] => [
  ["Passed", kpis.passedInspections],
  ["Failed", kpis.failedInspections],
  ["NCRs closed", kpis.closedNcrs],
  ["Open CAPAs", kpis.openCapas],
  ["Supplier lot rejection", rate(kpis.supplierRejectionRate)],
  ["Material rejection", rate(kpis.materialRejectionRate)],
  ["Defect rate", rate(kpis.defectRate)],
  ["First pass yield", rate(kpis.firstPassYield)],
  ["Inspection turnaround", hours(kpis.turnaroundHours)],
];

export function KpiTiles({ kpis, all = false }: { kpis: Kpis; all?: boolean }) {
  const tiles = all ? [...KEY(kpis), ...REST(kpis)] : KEY(kpis);
  return (
    <dl className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
      {tiles.map(([label, value]) => (
        <div key={label} className="rounded-lg border p-3">
          <dt className="text-xs text-muted-foreground">{label}</dt>
          <dd className="text-xl font-semibold">{value}</dd>
        </div>
      ))}
    </dl>
  );
}
