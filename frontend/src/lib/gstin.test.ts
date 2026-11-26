import { describe, expect, it } from "vitest";
import { isValidGstin } from "./gstin";

describe("isValidGstin", () => {
  it("accepts a GSTIN whose check character is right", () => {
    expect(isValidGstin("27AAPFU0939F1ZV")).toBe(true);
    expect(isValidGstin("27AAKCD4321M1ZG")).toBe(true);
  });

  it("refuses a wrong check character or shape", () => {
    expect(isValidGstin("27AAKCD4321M1Z9")).toBe(false);
    expect(isValidGstin("27AAPFU0939F1XV")).toBe(false);
    expect(isValidGstin("ABC")).toBe(false);
  });
});
