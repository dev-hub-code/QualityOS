import { describe, expect, it } from "vitest";
import { describeSignals, formatIndex, formatMeasure, linear, stepPath, ticks, valueDomain, type SpcChart } from "./spc";

describe("formatting", () => {
  it("shows measurements to five significant figures", () => {
    expect(formatMeasure(20.006667)).toBe("20.007");
    expect(formatMeasure(0.0325)).toBe("0.0325");
    expect(formatMeasure(1250.5)).toBe("1250.5");
    expect(formatMeasure(123456.7)).toBe("123457");
    expect(formatMeasure(0)).toBe("0");
    expect(formatMeasure(null)).toBe("—");
  });

  it("shows indices to two decimals, and an empty one as a dash", () => {
    expect(formatIndex(1.3544)).toBe("1.35");
    expect(formatIndex(null)).toBe("—");
  });
});

describe("scales", () => {
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

  it("maps a domain onto pixels, upside down for a y axis", () => {
    const y = linear([0, 10], [200, 0]);
    expect(y(0)).toBe(200);
    expect(y(10)).toBe(0);
    expect(y(2.5)).toBe(150);
  });

  it("draws equal limits as one line, and steps them halfway between points that differ", () => {
    const x = (index: number) => 100 + index * 50;
    const y = (value: number) => value * 10;
    expect(stepPath([5, 5, 5], x, y, [100, 200])).toBe("M100,50 H125 H175 H200");
    expect(stepPath([5, 6, 6], x, y, [100, 200])).toBe("M100,50 H125 V60 H175 H200");
  });
});

describe("describeSignals", () => {
  const point = { inspectionId: "i1", inspectionNumber: "INS-1", batchNumber: "B-1", submittedAt: "2026-11-01T00:00:00Z",
    n: 5, lcl: 9, ucl: 11, rangeCentre: 1, rangeLcl: 0, rangeUcl: 2 };
  const chart = {
    chartType: "XBAR_R",
    centre: 10,
    points: [
      { ...point, value: 12, range: 1, signals: ["BEYOND_LIMITS"] },
      { ...point, inspectionId: "i2", value: 10.5, range: 3, signals: ["RANGE_BEYOND_LIMITS", "RUN_OF_8"] },
      { ...point, inspectionId: "i3", value: 10, range: 1, signals: [] },
    ],
  } as unknown as SpcChart;

  it("puts each signal in words, one line a point", () => {
    expect(describeSignals(chart).map((line) => line.text)).toEqual([
      "mean above the upper control limit",
      "range above the upper control limit; part of a run of 8 or more above the centre line",
    ]);
  });
});
