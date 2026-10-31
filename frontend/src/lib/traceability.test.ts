import { describe, expect, it } from "vitest";
import { inspectionTypeFor, parseSerials, toConsumeRequest } from "./traceability";

describe("parseSerials", () => {
  it("splits on lines and commas, and drops blanks and repeats", () => {
    expect(parseSerials(" A1\nA2, A3\n\nA1 ")).toEqual(["A1", "A2", "A3"]);
  });
});

describe("inspectionTypeFor", () => {
  it("follows the batch's stage", () => {
    expect(inspectionTypeFor({ status: "IN_PROGRESS", producedQuantity: null })).toBe("IPQC");
    expect(inspectionTypeFor({ status: "COMPLETED", producedQuantity: "5" })).toBe("FQC");
    expect(inspectionTypeFor({ status: "ON_HOLD", producedQuantity: null })).toBe("IPQC");
    expect(inspectionTypeFor({ status: "ON_HOLD", producedQuantity: "5" })).toBe("FQC");
  });

  it("offers nothing once the batch has a final outcome", () => {
    expect(inspectionTypeFor({ status: "RELEASED", producedQuantity: "5" })).toBeNull();
    expect(inspectionTypeFor({ status: "REJECTED", producedQuantity: "5" })).toBeNull();
  });
});

describe("toConsumeRequest", () => {
  it("names the right source", () => {
    expect(toConsumeRequest("lot:1", "2")).toEqual({ receiptItemId: "1", quantity: "2" });
    expect(toConsumeRequest("batch:9", "2")).toEqual({ parentBatchId: "9", quantity: "2" });
  });
});
