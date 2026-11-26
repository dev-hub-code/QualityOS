import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { RecallDashboardTiles } from "./recall-dashboard";

describe("RecallDashboardTiles", () => {
  it("shows every count", () => {
    render(
      <RecallDashboardTiles
        dashboard={{
          totalCustomers: 9, pending: 8, contactAttempts: 7, contacted: 6, acknowledged: 5, unreachable: 4,
          returnsInProgress: 3, resolved: 2, outstandingFollowUps: 1,
          dispatchedQuantity: "105.500000", returnedQuantity: "30.000000", outstandingQuantity: "75.500000",
          // Metres of wire and coils of it are shown side by side, not added together.
          totals: { dispatched: "100.5 M · 5 PCS", returned: "30 M", outstanding: "70.5 M · 5 PCS", oneUnit: false },
        }}
      />,
    );
    const value = (label: string) => within(screen.getByText(label).parentElement as HTMLElement).getByRole("definition");
    expect(value("Affected customers")).toHaveTextContent("9");
    expect(value("Contact attempts")).toHaveTextContent("7");
    expect(value("Unreachable")).toHaveTextContent("4");
    expect(value("Follow-ups due")).toHaveTextContent("1");
    expect(value("Still with customers")).toHaveTextContent("70.5 M · 5 PCS");
  });
});
