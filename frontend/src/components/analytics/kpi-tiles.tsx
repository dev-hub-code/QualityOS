import type { Kpis, ProductionFigures } from "@/lib/types";
import type { FigureAccess } from "@/lib/analytics-access";

export const rate = (value: number | null) => (value === null ? "No data" : `${value}%`);

function hours(value: number | null) {
  if (value === null) return "No data";
  return value < 48 ? `${value} h` : `${(value / 24).toFixed(1)} days`;
}

type Area = keyof FigureAccess;
type Figure = [label: string, value: string | number | null, area: Area];

const KEY = (kpis: Kpis): Figure[] => [
  ["Inspections decided", kpis.totalInspections, "inspections"],
  ["Pass rate", rate(kpis.inspectionPassRate), "inspections"],
  ["Open NCRs", kpis.openNcrs, "ncrs"],
  ["Overdue CAPAs", kpis.overdueCapas, "capas"],
];

const REST = (kpis: Kpis): Figure[] => [
  ["Passed", kpis.passedInspections, "inspections"],
  ["Failed", kpis.failedInspections, "inspections"],
  ["NCRs closed", kpis.closedNcrs, "ncrs"],
  ["Open CAPAs", kpis.openCapas, "capas"],
  ["Supplier lot rejection", rate(kpis.supplierRejectionRate), "suppliers"],
  ["Material rejection", rate(kpis.materialRejectionRate), "inspections"],
  ["Defect rate", rate(kpis.defectRate), "inspections"],
  ["First pass yield", rate(kpis.firstPassYield), "inspections"],
  ["Inspection turnaround", hours(kpis.turnaroundHours), "inspections"],
];

/** Minutes as hours once there are enough of them. */
export function downtime(minutes: number) {
  return minutes < 120 ? `${minutes} min` : `${(minutes / 60).toFixed(1)} h`;
}

const PRODUCTION = (figures: ProductionFigures): Figure[] => [
  ["Open work orders", figures.openWorkOrders, "production"],
  ["Late work orders", figures.lateWorkOrders, "production"],
  ["Work orders completed", figures.completedWorkOrders, "production"],
  ["Completed on time", rate(figures.onTimeRate), "production"],
  ["Shop-floor loss", rate(figures.lossRate), "production"],
  [
    "Unplanned downtime",
    figures.unplannedDowntimeMinutes === null ? null : downtime(figures.unplannedDowntimeMinutes),
    "downtime",
  ],
];

const EVERYTHING: FigureAccess = {
  inspections: true,
  ncrs: true,
  capas: true,
  suppliers: true,
  audits: true,
  production: true,
  downtime: true,
};

export function ProductionTiles({ figures, access = EVERYTHING }: { figures: ProductionFigures; access?: FigureAccess }) {
  return <Tiles tiles={PRODUCTION(figures)} access={access} />;
}

export function KpiTiles({ kpis, all = false, access = EVERYTHING }: { kpis: Kpis; all?: boolean; access?: FigureAccess }) {
  return <Tiles tiles={all ? [...KEY(kpis), ...REST(kpis)] : KEY(kpis)} access={access} />;
}

/** Leaves out figures this person may not see. */
function Tiles({ tiles, access }: { tiles: Figure[]; access: FigureAccess }) {
  const shown = tiles.filter(([, value, area]) => access[area] && value !== null);
  if (shown.length === 0) return null;
  return (
    <dl className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
      {shown.map(([label, value]) => (
        <div key={label} className="glass rounded-2xl p-3">
          <dt className="text-xs text-muted-foreground">{label}</dt>
          <dd className="text-xl font-semibold">{value}</dd>
        </div>
      ))}
    </dl>
  );
}
