import { describe, expect, it } from "vitest";
import { storageText, usageText } from "./usage";

describe("usage text", () => {
  it("shows used against the limit with a percentage", () => {
    expect(usageText(18, 20)).toBe("18 / 20 (90%)");
  });

  it("gives no percentage when there is no limit", () => {
    expect(usageText(18, null)).toBe("18 (no limit)");
  });

  it("states storage in the larger unit once it reaches a gigabyte", () => {
    expect(storageText(4.2 * 1024 * 1_048_576, 10 * 1024)).toBe("4.2 GB / 10.0 GB (42%)");
    expect(storageText(5 * 1_048_576, null)).toBe("5.0 MB (no limit)");
  });
});
