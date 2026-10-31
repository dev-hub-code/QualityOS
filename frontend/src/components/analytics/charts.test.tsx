import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { MonthBars, RankBars } from "./charts";

describe("charts", () => {
  it("says when there is nothing to draw", () => {
    render(<RankBars rows={[]} headings={["A", "B"]} />);
    expect(screen.getByText("Nothing to show for this period.")).toBeInTheDocument();
  });

  it("gives each month a name that does not depend on colour, and a legend", () => {
    render(
      <MonthBars
        data={[{ month: "2026-10", passed: 3, failed: 1 }]}
        series={[{ key: "passed", label: "Passed" }, { key: "failed", label: "Failed" }]}
      />,
    );
    expect(screen.getByLabelText("2026-10: 3 Passed, 1 Failed")).toBeInTheDocument();
    expect(screen.getByRole("list", { name: "Legend" })).toHaveTextContent("Failed");
    expect(screen.getByText("Show as a table")).toBeInTheDocument();
  });

  it("treats months with nothing in them as nothing to draw", () => {
    render(
      <MonthBars
        data={[{ month: "2026-10", opened: 0, closed: 0 }]}
        series={[{ key: "opened", label: "Opened" }, { key: "closed", label: "Closed" }]}
        grouped
      />,
    );
    expect(screen.getByText("Nothing to show for this period.")).toBeInTheDocument();
  });

  it("scales percentages to a hundred, so that a lone low rate stays small", () => {
    render(<RankBars max={100} rows={[{ label: "Acme", value: 5, display: "5%" }]} headings={["A", "B"]} />);
    const bar = screen.getByLabelText("Acme: 5%").querySelector("div") as HTMLElement;
    expect(bar.style.width).toBe("5%");
  });
});
