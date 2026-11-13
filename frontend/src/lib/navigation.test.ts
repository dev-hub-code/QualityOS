import { describe, expect, it } from "vitest";
import {
  activeNavHref,
  forManufacturingModel,
  landingPage,
  leavesWorkspace,
  NAV_ITEMS,
  visibleNavItems,
  workspaceNavItems,
} from "./navigation";

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
    expect(leavesWorkspace("/settings/billing", ["PLATFORM_ADMIN"], false)).toBe(true);
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

describe("activeNavHref", () => {
  it("highlights serial search, not traceability, on a serial page", () => {
    expect(activeNavHref("/traceability/serials", NAV_ITEMS)).toBe("/traceability/serials");
    expect(activeNavHref("/traceability/serials/SN-1", NAV_ITEMS)).toBe("/traceability/serials");
  });

  it("keeps traceability for a batch trace", () => {
    expect(activeNavHref("/traceability/PB-2026-00001", NAV_ITEMS)).toBe("/traceability");
  });

  it("highlights billing, not settings, on the billing page", () => {
    expect(activeNavHref("/settings/billing", NAV_ITEMS)).toBe("/settings/billing");
    expect(activeNavHref("/settings", NAV_ITEMS)).toBe("/settings");
  });

  it("does not match a longer word that only starts the same", () => {
    expect(activeNavHref("/materials-x", NAV_ITEMS)).toBeUndefined();
  });
});

describe("forManufacturingModel", () => {
  const manufacturing = visibleNavItems(["MANUFACTURING_VIEW"]).filter((item) => item.group === "Manufacturing");
  const shown = (model?: "DISCRETE" | "PROCESS" | "HYBRID") =>
    forManufacturingModel(manufacturing, model).map((item) => item.label);

  it("shows BOMs to discrete, recipes to process, and both to hybrid organisations", () => {
    expect(shown("DISCRETE")).toContain("BOMs");
    expect(shown("DISCRETE")).not.toContain("Recipes");
    expect(shown("PROCESS")).toEqual(["Work centres", "Machines", "Recipes", "Routings", "Quality checkpoints", "Configuration"]);
    expect(shown("HYBRID")).toEqual(expect.arrayContaining(["BOMs", "Recipes"]));
  });

  it("keeps the group together and hidden from people without the permission", () => {
    const indexes = NAV_ITEMS.flatMap((item, index) => (item.group === "Manufacturing" ? [index] : []));
    expect(indexes.at(-1)! - indexes[0]).toBe(indexes.length - 1);
    expect(visibleNavItems(["NCR_VIEW"]).some((item) => item.group === "Manufacturing")).toBe(false);
  });

  it("shows plans and work orders to whoever can see work orders, in every model", () => {
    const labels = forManufacturingModel(visibleNavItems(["WORK_ORDER_VIEW"]), "PROCESS").map((item) => item.label);
    expect(labels).toEqual(expect.arrayContaining(["Production plans", "Work orders"]));
    expect(labels).not.toContain("Machines");
  });
});
