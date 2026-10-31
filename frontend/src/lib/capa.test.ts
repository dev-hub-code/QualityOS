import { describe, expect, it } from "vitest";
import { humanize, isReadyForVerification, toFishbone, toFiveWhys } from "./capa";

describe("toFiveWhys", () => {
  it("drops blanks and keeps the order", () => {
    expect(toFiveWhys([" Burrs ", "", "Worn tool", "  ", ""])).toEqual(["Burrs", "Worn tool"]);
  });
});

describe("toFishbone", () => {
  it("reads one cause per line and leaves out empty branches", () => {
    expect(toFishbone({ Machine: "Worn insert\n\n Loose chuck ", Man: "  " })).toEqual({
      Machine: ["Worn insert", "Loose chuck"],
    });
  });
});

describe("isReadyForVerification", () => {
  it("needs every action done", () => {
    expect(isReadyForVerification([])).toBe(false);
    expect(isReadyForVerification([{ completedAt: "2026-10-31" }, { completedAt: null }])).toBe(false);
    expect(isReadyForVerification([{ completedAt: "2026-10-31" }])).toBe(true);
  });
});

describe("humanize", () => {
  it("reads a status as words", () => {
    expect(humanize("PENDING_VERIFICATION")).toBe("pending verification");
  });
});
