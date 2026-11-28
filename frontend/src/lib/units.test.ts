import { describe, expect, it } from "vitest";
import type { Unit } from "@/lib/types";
import { equivalence, unitRequest } from "@/lib/units";

const unit = (code: string, dimension: string, toBase: string | null): Unit => ({
  id: code,
  code,
  name: code,
  dimension,
  toBase,
  system: true,
});
const units = [unit("KG", "Mass", "1"), unit("GM", "Mass", "0.001"), unit("MG", "Mass", "0.000001"), unit("HRC", "Hardness", null)];

describe("equivalence", () => {
  it("says a unit in its kind's base unit", () => {
    expect(equivalence(units[2], units)).toBe("1 MG = 0.000001 KG");
    expect(equivalence(units[0], units)).toBe("Base unit");
  });

  it("marks a unit that never converts", () => {
    expect(equivalence(units[3], units)).toBe("–");
  });
});

describe("unitRequest", () => {
  it("leaves out what was not filled in", () => {
    expect(unitRequest({ code: "CP", name: "Centipoise", equalsQuantity: "", equalsUnitId: "" })).toEqual({
      code: "CP",
      name: "Centipoise",
    });
  });
});
