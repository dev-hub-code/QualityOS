import type { Kpis, ProductionFigures } from "@/lib/types";

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

/** Minutes as hours once there are enough of them. */
export function downtime(minutes: number) {
  return minutes < 120 ? `${minutes} min` : `${(minutes / 60).toFixed(1)} h`;
}

const PRODUCTION = (figures: ProductionFigures): [string, string | number][] => [
  ["Open work orders", figures.openWorkOrders],
  ["Late work orders", figures.lateWorkOrders],
  ["Work orders completed", figures.completedWorkOrders],
  ["Completed on time", rate(figures.onTimeRate)],
  ["Shop-floor loss", rate(figures.lossRate)],
  ["Unplanned downtime", downtime(figures.unplannedDowntimeMinutes)],
];

export function ProductionTiles({ figures }: { figures: ProductionFigures }) {
  return <Tiles tiles={PRODUCTION(figures)} />;
}

export function KpiTiles({ kpis, all = false }: { kpis: Kpis; all?: boolean }) {
  return <Tiles tiles={all ? [...KEY(kpis), ...REST(kpis)] : KEY(kpis)} />;
}

function Tiles({ tiles }: { tiles: [string, string | number][] }) {
  return (
    <dl className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
      {tiles.map(([label, value]) => (
        <div key={label} className="glass rounded-2xl p-3">
          <dt className="text-xs text-muted-foreground">{label}</dt>
          <dd className="text-xl font-semibold">{value}</dd>
        </div>
      ))}
    </dl>
  );
}
