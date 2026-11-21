import { describe, expect, it } from "vitest";
import { findState, stateFromGstin, type PlaceState } from "./places";

const STATES: PlaceState[] = [
  { gstCode: "27", name: "Maharashtra", kind: "STATE" },
  { gstCode: "07", name: "Delhi", kind: "UNION_TERRITORY" },
];

describe("findState", () => {
  it("matches a typed name whatever its case and spacing", () => {
    expect(findState(STATES, " maharashtra ")?.gstCode).toBe("27");
    expect(findState(STATES, "Delhi")?.gstCode).toBe("07");
  });

  it("finds nothing for another name or no name", () => {
    expect(findState(STATES, "Maha")).toBeUndefined();
    expect(findState(STATES, "")).toBeUndefined();
    expect(findState(STATES, null)).toBeUndefined();
  });
});

describe("stateFromGstin", () => {
  it("reads the state from the first two digits", () => {
    expect(stateFromGstin(STATES, "27AAPFU0939F1ZV")?.name).toBe("Maharashtra");
    expect(stateFromGstin(STATES, " 07")?.name).toBe("Delhi");
  });

  it("waits for two digits that name a state", () => {
    expect(stateFromGstin(STATES, "2")).toBeUndefined();
    expect(stateFromGstin(STATES, "99AAPFU0939F1ZV")).toBeUndefined();
    expect(stateFromGstin(STATES, "AB")).toBeUndefined();
    expect(stateFromGstin(STATES, null)).toBeUndefined();
  });
});
