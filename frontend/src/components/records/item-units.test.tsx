import { describe, expect, it } from "vitest";
import { enteredLabel } from "@/components/records/item-units";

describe("enteredLabel", () => {
  it("shows what was counted beside the stock quantity", () => {
    expect(enteredLabel("800.000000", "KG", { quantity: "4.000000", unitCode: "DRUM" })).toBe("4 DRUM (800 KG)");
  });

  it("shows the stock quantity alone when it was entered in the item's own unit", () => {
    expect(enteredLabel("158.760000", "KG", { quantity: null, unitCode: null })).toBe("158.76 KG");
  });
});
