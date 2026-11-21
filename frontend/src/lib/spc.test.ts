import { describe, expect, it } from "vitest";
import { capabilityVerdict, formatIndex, formatMeasure, ticks, valueDomain } from "./spc";

describe("capabilityVerdict", () => {
  it("reads Cpk the usual way", () => {
    expect(capabilityVerdict(1.67)?.label).toBe("Capable");
    expect(capabilityVerdict(1.33)?.status).toBe("good");
    expect(capabilityVerdict(1.1)?.label).toBe("Marginal");
    expect(capabilityVerdict(0.8)?.status).toBe("critical");
    expect(capabilityVerdict(null)).toBeNull();
  });
});

describe("formatting", () => {
  it("shows measurements to five significant figures", () => {
    expect(formatMeasure(20.006667)).toBe("20.007");
    expect(formatMeasure(0.0325)).toBe("0.0325");
    expect(formatMeasure(1250.5)).toBe("1250.5");
    expect(formatMeasure(123456.7)).toBe("123457");
    expect(formatMeasure(0)).toBe("0");
    expect(formatMeasure(null)).toBe("—");
  });

  it("shows indices to two decimals", () => {
    expect(formatIndex(1.3544)).toBe("1.35");
    expect(formatIndex(null)).toBe("—");
  });
});

describe("axis", () => {
  it("leaves room around what is drawn, and some around a flat line", () => {
    const [low, high] = valueDomain([10, 20, null]);
    expect(low).toBeLessThan(10);
    expect(high).toBeGreaterThan(20);
    expect(valueDomain([5, 5])).toEqual([4.75, 5.25]);
  });

  it("puts ticks on round steps inside the axis", () => {
    expect(ticks([19.85, 20.15])).toEqual([19.9, 20, 20.1]);
    expect(ticks([0, 10])).toEqual([0, 2, 4, 6, 8, 10]);
  });
});
