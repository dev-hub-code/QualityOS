import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { PanelPoint, SpcChart } from "@/lib/spc";
import { ControlChart, MARGIN } from "./control-chart";
import { CapabilitySummary, SignalList, SpcAssumptions } from "./spc-summary";

const panel = (values: [number, number, number, boolean][]): PanelPoint[] =>
  values.map(([value, lower, upper, signal], index) => ({
    key: `p${index}`,
    label: `INS-${index + 1} · batch B-${index + 1}`,
    inspectionId: `i${index + 1}`,
    submittedAt: `2026-11-0${index + 1}T10:00:00Z`,
    value,
    centre: 10,
    lower,
    upper,
    signal,
  }));

function chart(overrides: Partial<SpcChart> = {}): SpcChart {
  return {
    templateId: "t", templateCode: "IQC-SHAFT", templateName: "Shaft", from: "2026-08-24", to: "2026-11-21",
    chartType: "XBAR_R",
    parameter: { code: "DIA", name: "Diameter", unit: "mm", lsl: 19.9, usl: 20.1, nominal: 20, specChanged: false, specVersion: 3 },
    centre: 20.003, sigma: 0.0211, capability: { cp: 1.58, cpk: 1.53 },
    points: [
      { inspectionId: "i1", inspectionNumber: "INS-000412", batchNumber: "B-77", submittedAt: "2026-11-02T04:44:00Z",
        n: 5, value: 20.05, lcl: 19.975, ucl: 20.031, range: 0.05, rangeCentre: 0.049, rangeLcl: 0, rangeUcl: 0.104,
        signals: ["BEYOND_LIMITS"] },
    ],
    excluded: { singleReading: 0, tooLarge: 0 }, truncated: false, subgroupsInPeriod: 1, readingsInPeriod: 5,
    preliminary: false, warnings: [],
    ...overrides,
  };
}

describe("ControlChart", () => {
  it("places the limits by the scale, stepping where a point's limits differ", () => {
    const { container } = render(
      <ControlChart title="X̄" valueName="Mean" unit={null} points={panel([[10, 8, 12, false], [11, 9, 11, false], [10.5, 9, 11, false]])} />,
    );
    const ucl = container.querySelector('[data-line="UCL"]')?.getAttribute("d") ?? "";
    const cl = container.querySelector('[data-line="CL"]')?.getAttribute("d") ?? "";
    // The UCL steps once (12 to 11); the CL stays level.
    expect(ucl.match(/V/g)).toHaveLength(1);
    expect(cl).not.toContain("V");
    expect(ucl.startsWith(`M${MARGIN.left},`)).toBe(true);
    // Higher values sit higher: the first UCL level (12) is above the centre line (10).
    const firstY = (d: string) => Number(/^M[\d.]+,([\d.]+)/.exec(d)?.[1]);
    expect(firstY(ucl)).toBeLessThan(firstY(cl));
    expect(screen.getByText("Limits step with each inspection's sample size.")).toBeInTheDocument();
  });

  it("marks signal points, and the table lists every point", () => {
    const { container } = render(
      <ControlChart title="X̄" valueName="Mean" unit="mm" points={panel([[10, 9, 11, false], [12, 9, 11, true], [10, 9, 11, false]])} />,
    );
    expect(container.querySelectorAll("circle[data-signal]")).toHaveLength(1);
    expect(screen.getByText("1 signal")).toBeInTheDocument();
    expect(screen.queryByText(/Limits step/)).toBeNull();
    const rows = within(container.querySelector("tbody") as HTMLElement).getAllByRole("row");
    expect(rows).toHaveLength(3);
    expect(within(rows[1]).getByRole("link", { name: "INS-2 · batch B-2" })).toHaveAttribute("href", "/inspections/i2");
    expect(within(rows[1]).getByText("Yes")).toBeInTheDocument();
  });
});

describe("CapabilitySummary", () => {
  it("shows the figures and the specification", () => {
    render(<CapabilitySummary chart={chart()} />);
    expect(screen.getByText("1.58")).toBeInTheDocument();
    expect(screen.getByText("1.53")).toBeInTheDocument();
    expect(screen.getByText("19.9 to 20.1 mm")).toBeInTheDocument();
  });

  it("reads a dash when capability is empty, with the warning shown", () => {
    const warning = "The parameter has no specification limits, so Cp and Cpk cannot be worked out.";
    render(<CapabilitySummary chart={chart({
      capability: { cp: null, cpk: null },
      parameter: { code: "DIA", name: null, unit: null, lsl: null, usl: null, nominal: null, specChanged: false, specVersion: 1 },
      warnings: [warning],
      excluded: { singleReading: 2, tooLarge: 0 },
    })} />);
    expect(screen.getAllByText("—")).toHaveLength(2);
    expect(screen.getByText(warning)).toBeInTheDocument();
    expect(screen.getByText("2 inspections with a single reading left out of the X̄-R chart.")).toBeInTheDocument();
  });
});

describe("trust in the figures", () => {
  it("marks preliminary Cp and Cpk, and names the specification version", () => {
    render(<CapabilitySummary chart={chart({ preliminary: true })} />);
    expect(screen.getAllByText("Preliminary")).toHaveLength(2);
    expect(screen.getByText("Spec LSL to USL · v3")).toBeInTheDocument();
  });

  it("says how much of the period is charted, and what was left out", () => {
    render(<CapabilitySummary chart={chart({ truncated: true, subgroupsInPeriod: 812, excluded: { singleReading: 2, tooLarge: 0 } })} />);
    expect(screen.getByText("Charting the latest 3 of 812 inspections in the period; 1 plotted, 2 left out.")).toBeInTheDocument();
  });

  it("tells control limits from specification limits", () => {
    render(<ControlChart title="X̄" valueName="Mean" unit={null} points={panel([[10, 9, 11, false]])} spec={{ lower: 8, upper: 12, version: 3 }} />);
    expect(screen.getByText("Control limits: how the process behaves")).toBeInTheDocument();
    expect(screen.getByText("Specification limits: what the design requires (v3)")).toBeInTheDocument();
  });

  it("states what the figures assume", () => {
    render(<SpcAssumptions chart={chart({ chartType: "I_MR" })} />);
    expect(screen.getByText(/Cp and Cpk assume a stable process/)).toBeInTheDocument();
    expect(screen.getByText(/lot-to-lot changes count as variation/)).toBeInTheDocument();
  });
});

describe("SignalList", () => {
  it("lists each signal with a link to its inspection", () => {
    render(<SignalList chart={chart()} />);
    expect(screen.getByRole("link", { name: "INS-000412" })).toHaveAttribute("href", "/inspections/i1");
    expect(screen.getByText(/mean above the upper control limit/)).toBeInTheDocument();
  });
});
