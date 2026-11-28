import { describe, expect, it } from "vitest";
import {
  conversionChoices,
  enteredLabel,
  factorProblem,
} from "@/components/records/item-units";
import type { Unit } from "@/lib/types";

describe("enteredLabel", () => {
  it("shows what was counted beside the stock quantity", () => {
    expect(
      enteredLabel("800.000000", "KG", {
        quantity: "4.000000",
        unitCode: "DRUM",
      }),
    ).toBe("4 DRUM (800 KG)");
  });

  it("shows the stock quantity alone when it was entered in the item's own unit", () => {
    expect(
      enteredLabel("158.760000", "KG", { quantity: null, unitCode: null }),
    ).toBe("158.76 KG");
  });
});

describe("conversionChoices", () => {
  const unit = (
    code: string,
    dimension: string,
    toBase: string | null,
    system = true,
  ): Unit => ({
    id: code,
    code,
    name: code,
    dimension,
    toBase,
    system,
  });
  const kg = unit("KG", "Mass", "1");
  const units = [
    kg,
    unit("GM", "Mass", "0.001"),
    unit("LTR", "Volume", "1"),
    unit("ML", "Volume", "0.001"),
    unit("°C", "Temperature", null),
    unit("%", "Ratio", null),
    unit("DRUM", "Other", null, false),
  ];
  const codes = (list: Unit[]) => list.map((one) => one.code);

  it("offers packs and other kinds, but not the item's own kind or readings", () => {
    expect(codes(conversionChoices(units, kg, []))).toEqual([
      "LTR",
      "ML",
      "DRUM",
    ]);
  });

  it("leaves out a unit already reached through one of the item's conversions", () => {
    expect(codes(conversionChoices(units, kg, [units[2]]))).toEqual(["DRUM"]);
  });
});

describe("factorProblem", () => {
  it("takes a plain number above zero", () => {
    expect(factorProblem("25", "BAG", "KG")).toBe("");
    expect(factorProblem(" 0.96 ", "LTR", "KG")).toBe("");
  });

  it("says what to type for zero, a negative or a word", () => {
    for (const typed of ["0", "-2", "abc", "1,5", ""]) {
      expect(factorProblem(typed, "PAIL", "LTR")).toBe(
        "Enter how many LTR one PAIL holds, as a number above zero such as 25 or 0.96.",
      );
    }
  });
});
