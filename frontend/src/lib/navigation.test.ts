import { describe, expect, it } from "vitest";
import { landingPage, leavesWorkspace, visibleNavItems, workspaceNavItems } from "./navigation";

const labels = (permissions: string[]) => visibleNavItems(permissions).map((item) => item.label);

describe("landingPage", () => {
  it("sends platform operators to the Control Center", () => {
    expect(landingPage(["PLATFORM_ADMIN", "TENANT_MANAGE"])).toBe("/platform/dashboard");
  });

  it("sends everyone else to their organisation", () => {
    expect(landingPage(["TENANT_MANAGE", "USER_MANAGE"])).toBe("/dashboard");
    expect(landingPage([])).toBe("/dashboard");
  });
});

describe("workspaceNavItems", () => {
  const permissions = ["PLATFORM_ADMIN", "SUBSCRIPTION_MANAGE", "PURCHASE_VIEW"];

  it("keeps the whole workspace for a subscribed organisation", () => {
    expect(workspaceNavItems(permissions, true)).toEqual(visibleNavItems(permissions));
  });

  it("leaves a platform organisation only its account pages", () => {
    expect(workspaceNavItems(permissions, false).map((item) => item.label)).toEqual([
      "Settings",
      "Documentation",
      "Control Center",
    ]);
  });
});

describe("leavesWorkspace", () => {
  it("moves an operator of a platform organisation off workspace pages", () => {
    expect(leavesWorkspace("/dashboard", ["PLATFORM_ADMIN"], false)).toBe(true);
    expect(leavesWorkspace("/ncrs/1", ["PLATFORM_ADMIN"], false)).toBe(true);
  });

  it("leaves their account pages and the Control Center alone", () => {
    for (const page of ["/settings", "/docs", "/platform/dashboard"]) {
      expect(leavesWorkspace(page, ["PLATFORM_ADMIN"], false)).toBe(false);
    }
  });

  it("leaves customers and operators with a workspace alone", () => {
    expect(leavesWorkspace("/dashboard", ["PLATFORM_ADMIN"], true)).toBe(false);
    expect(leavesWorkspace("/dashboard", [], false)).toBe(false);
  });
});

describe("visibleNavItems", () => {
  it("hides administration from users without permissions", () => {
    expect(labels([])).toEqual(["Dashboard", "Settings", "Documentation"]);
  });

  it("shows master data by module permission", () => {
    expect(labels(["PURCHASE_VIEW"])).toEqual(["Dashboard", "Purchase orders", "Receipts", "Settings", "Documentation"]);
  });

  it("shows items for any matching permission", () => {
    expect(labels(["USER_MANAGE"])).toEqual(["Dashboard", "Users", "Roles", "Settings", "Documentation"]);
    expect(labels(["ROLE_MANAGE"])).toEqual(["Dashboard", "Roles", "Settings", "Documentation"]);
  });
});
