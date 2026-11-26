import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { InstrumentUsage } from "./calibration-history";

const inspection = (
  id: string,
  number: string,
  usedOn: string,
  suspect: boolean,
) => ({
  inspectionId: id,
  inspectionNumber: number,
  label: number,
  link: `/inspections/${id}`,
  usedOn,
  suspect,
});

describe("InstrumentUsage", () => {
  it("warns about records put in doubt by a failed calibration, inspections and shop-floor operations alike", () => {
    render(
      <InstrumentUsage
        uses={[
          inspection("a", "INS-1", "2026-10-01", true),
          {
            inspectionId: null,
            inspectionNumber: null,
            label: "WO-2026-00001 operation 10",
            link: "/manufacturing/shop-floor/op",
            usedOn: "2026-09-20",
            suspect: true,
          },
          inspection("b", "INS-2", "2026-09-01", false),
        ]}
      />,
    );
    expect(
      screen.getByText(/2 records have used this instrument/),
    ).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "INS-1" })).toHaveAttribute(
      "href",
      "/inspections/a",
    );
    expect(
      screen.getByRole("link", { name: "WO-2026-00001 operation 10" }),
    ).toHaveAttribute("href", "/manufacturing/shop-floor/op");
  });

  it("stays quiet when nothing is in doubt", () => {
    render(
      <InstrumentUsage
        uses={[inspection("a", "INS-1", "2026-10-01", false)]}
      />,
    );
    expect(screen.queryByText(/Review them/)).not.toBeInTheDocument();
  });
});
