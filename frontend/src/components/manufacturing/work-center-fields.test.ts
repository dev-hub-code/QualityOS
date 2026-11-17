import { describe, expect, it } from "vitest";
import { workCenterRecord, workCenterRequest } from "./work-center-fields";

describe("work centre shifts", () => {
  it("ticks the shifts a centre names and sends back the ticked ones", () => {
    const record = workCenterRecord({ capacityUnits: 2, shiftIds: ["s1"] });
    expect(record).toMatchObject({ capacityUnits: "2", worksShift_s1: true });
    expect(workCenterRequest({ code: "WC", worksShift_s1: true, worksShift_s2: false })).toEqual({
      code: "WC",
      shiftIds: ["s1"],
    });
    expect(workCenterRequest({ code: "WC" }).shiftIds).toEqual([]);
  });
});
