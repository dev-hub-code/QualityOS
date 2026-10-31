import { describe, expect, it } from "vitest";
import { requirement, toEntries, toFormValues } from "./inspection";
import type { ParameterSpec } from "./types";

const parameter = (overrides: Partial<ParameterSpec>): ParameterSpec => ({
  code: "DIA",
  name: "Diameter",
  description: null,
  dataType: "NUMERIC",
  uomId: null,
  nominalValue: null,
  minValue: null,
  maxValue: null,
  tolerance: null,
  expectedText: null,
  criticality: "MINOR",
  mandatory: true,
  perSample: true,
  evidenceRequired: false,
  inspectionMethod: null,
  remarks: null,
  ...overrides,
});

describe("requirement", () => {
  it("describes numeric limits", () => {
    expect(requirement(parameter({ minValue: "19.900000", maxValue: "20.100000" }))).toBe("19.9 to 20.1");
    expect(requirement(parameter({ nominalValue: "20.000000", tolerance: "0.100000" }))).toBe("20 ± 0.1");
    expect(requirement(parameter({ minValue: "50.000000" }))).toBe("At least 50");
    expect(requirement(parameter({}))).toBe("Reviewer decides");
  });
});

describe("toEntries", () => {
  it("skips blanks and types each value", () => {
    const parameters = [parameter({}), parameter({ code: "OK", dataType: "BOOLEAN", perSample: false })];

    const { entries, invalid } = toEntries(parameters, 2, { "DIA#2": " 20.05 ", "OK#1": "false" });

    expect(invalid).toEqual([]);
    expect(entries).toEqual([
      { parameterCode: "DIA", sampleNumber: 2, valueNumeric: "20.05" },
      { parameterCode: "OK", sampleNumber: 1, valueBoolean: false },
    ]);
  });

  it("reports numbers that cannot be stored exactly", () => {
    expect(toEntries([parameter({})], 1, { "DIA#1": "1.0000001" }).invalid).toEqual(["DIA sample 1"]);
    expect(toEntries([parameter({})], 1, { "DIA#1": "abc" }).entries).toEqual([]);
    expect(toEntries([parameter({})], 1, { "DIA#1": "-40" }).invalid).toEqual([]);
  });

  it("does not offer a second sample for a per-lot parameter", () => {
    const lot = parameter({ perSample: false });
    expect(toEntries([lot], 3, { "DIA#2": "5" }).entries).toEqual([]);
  });
});

describe("toFormValues", () => {
  it("restores saved values, including false", () => {
    const saved = {
      parameterCode: "OK",
      sampleNumber: 1,
      valueNumeric: null,
      valueText: null,
      valueBoolean: false,
      valueDate: null,
      evaluation: "FAIL",
      remarks: null,
    };
    expect(toFormValues([saved])).toEqual({ "OK#1": "false" });
  });
});
