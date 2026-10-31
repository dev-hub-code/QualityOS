import { describe, expect, it } from "vitest";
import { presetPeriod } from "./platform-period";

const today = new Date(2026, 2, 15);

describe("presetPeriod", () => {
  it("counts today in a rolling period", () => {
    expect(presetPeriod("LAST_7", today)).toEqual({ from: "2026-03-09", to: "2026-03-15" });
  });

  it("covers the whole previous month across a year end", () => {
    expect(presetPeriod("LAST_MONTH", new Date(2026, 0, 10))).toEqual({ from: "2025-12-01", to: "2025-12-31" });
  });

  it("runs the current month up to today", () => {
    expect(presetPeriod("THIS_MONTH", today)).toEqual({ from: "2026-03-01", to: "2026-03-15" });
  });
});
