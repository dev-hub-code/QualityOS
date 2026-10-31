import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { Kpis } from "@/lib/types";
import { KpiTiles } from "./kpi-tiles";

const KPIS: Kpis = {
  totalInspections: 4, passedInspections: 3, failedInspections: 1, inspectionPassRate: 75, openNcrs: 2, closedNcrs: 1,
  openCapas: 1, overdueCapas: 0, supplierRejectionRate: null, materialRejectionRate: 12.5, defectRate: 0,
  firstPassYield: 100, turnaroundHours: 72,
};
const value = (label: string) => within(screen.getByText(label).parentElement as HTMLElement).getByRole("definition");

describe("KpiTiles", () => {
  it("tells no data from zero", () => {
    render(<KpiTiles kpis={KPIS} all />);
    expect(value("Supplier lot rejection")).toHaveTextContent("No data");
    expect(value("Defect rate")).toHaveTextContent("0%");
  });

  it("shows long turnaround in days", () => {
    render(<KpiTiles kpis={KPIS} all />);
    expect(value("Inspection turnaround")).toHaveTextContent("3.0 days");
  });

  it("keeps the opening page to the four key figures", () => {
    render(<KpiTiles kpis={KPIS} />);
    expect(screen.getAllByRole("definition")).toHaveLength(4);
  });
});
