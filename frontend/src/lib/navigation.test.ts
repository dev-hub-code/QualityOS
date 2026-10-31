import { describe, expect, it } from "vitest";
import { visibleNavItems } from "./navigation";

const labels = (permissions: string[]) => visibleNavItems(permissions).map((item) => item.label);

describe("visibleNavItems", () => {
  it("hides administration from users without permissions", () => {
    expect(labels([])).toEqual(["Dashboard", "Settings"]);
  });

  it("shows master data by module permission", () => {
    expect(labels(["PURCHASE_VIEW"])).toEqual(["Dashboard", "Purchase orders", "Receipts", "Settings"]);
  });

  it("shows items for any matching permission", () => {
    expect(labels(["USER_MANAGE"])).toEqual(["Dashboard", "Users", "Roles", "Settings"]);
    expect(labels(["ROLE_MANAGE"])).toEqual(["Dashboard", "Roles", "Settings"]);
  });
});
