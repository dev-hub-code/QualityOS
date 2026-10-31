import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { InstrumentUsage } from "./calibration-history";

describe("InstrumentUsage", () => {
  it("warns about inspections put in doubt by a failed calibration", () => {
    render(
      <InstrumentUsage
        uses={[
          { inspectionId: "a", inspectionNumber: "INS-1", usedOn: "2026-10-01", suspect: true },
          { inspectionId: "b", inspectionNumber: "INS-2", usedOn: "2026-09-01", suspect: false },
        ]}
      />,
    );
    expect(screen.getByText(/1 inspection\(s\) used this instrument/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "INS-1" })).toHaveAttribute("href", "/inspections/a");
  });

  it("stays quiet when nothing is in doubt", () => {
    render(<InstrumentUsage uses={[{ inspectionId: "a", inspectionNumber: "INS-1", usedOn: "2026-10-01", suspect: false }]} />);
    expect(screen.queryByText(/Review them/)).not.toBeInTheDocument();
  });
});
