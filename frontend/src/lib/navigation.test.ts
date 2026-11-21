import { describe, expect, it } from "vitest";
import {
  activeNavHref,
  forManufacturingModel,
  landingPage,
  leavesWorkspace,
  NAV_GROUPS,
  NAV_ITEMS,
  navSections,
  visibleNavItems,
  workspaceNavItems,
} from "./navigation";

const labels = (permissions: string[]) =>
  visibleNavItems(permissions).map((item) => item.label);

describe("landingPage", () => {
  it("sends platform operators to the Control Center", () => {
    expect(landingPage(["PLATFORM_ADMIN", "TENANT_MANAGE"])).toBe(
      "/platform/dashboard",
    );
  });

  it("sends everyone else to their organisation", () => {
    expect(landingPage(["TENANT_MANAGE", "USER_MANAGE"])).toBe("/dashboard");
    expect(landingPage([])).toBe("/dashboard");
  });
});

describe("workspaceNavItems", () => {
  const permissions = [
    "PLATFORM_ADMIN",
    "SUBSCRIPTION_MANAGE",
    "PURCHASE_VIEW",
  ];

  it("keeps the whole workspace for a subscribed organisation", () => {
    expect(workspaceNavItems(permissions, true)).toEqual(
      visibleNavItems(permissions),
    );
  });

  it("leaves a platform organisation only its account pages", () => {
    expect(
      workspaceNavItems(permissions, false).map((item) => item.label),
    ).toEqual(["My account", "Documentation", "Control Center"]);
  });
});

describe("leavesWorkspace", () => {
  it("moves an operator of a platform organisation off workspace pages", () => {
    expect(leavesWorkspace("/dashboard", ["PLATFORM_ADMIN"], false)).toBe(true);
    expect(leavesWorkspace("/ncrs/1", ["PLATFORM_ADMIN"], false)).toBe(true);
    expect(
      leavesWorkspace("/settings/billing", ["PLATFORM_ADMIN"], false),
    ).toBe(true);
    expect(
      leavesWorkspace("/settings/organisation", ["PLATFORM_ADMIN"], false),
    ).toBe(true);
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
    expect(labels([])).toEqual([
      "Dashboard",
      "Settings",
      "Holiday calendar",
      "My account",
      "Support",
      "Documentation",
    ]);
  });

  it("shows master data by module permission", () => {
    expect(labels(["PURCHASE_VIEW"])).toEqual([
      "Dashboard",
      "Purchase orders",
      "Receipts",
      "Settings",
      "Holiday calendar",
      "My account",
      "Support",
      "Documentation",
    ]);
  });

  it("shows items for any matching permission", () => {
    expect(labels(["USER_MANAGE"])).toEqual([
      "Dashboard",
      "Users",
      "Roles",
      "Settings",
      "Holiday calendar",
      "My account",
      "Support",
      "Documentation",
    ]);
    expect(labels(["ROLE_MANAGE"])).toEqual([
      "Dashboard",
      "Roles",
      "Settings",
      "Holiday calendar",
      "My account",
      "Support",
      "Documentation",
    ]);
  });

  it("shows the audit log to those allowed to read it", () => {
    expect(labels(["AUDIT_LOG_VIEW"])).toEqual([
      "Dashboard",
      "Settings",
      "Audit log",
      "Holiday calendar",
      "My account",
      "Support",
      "Documentation",
    ]);
  });
});

describe("activeNavHref", () => {
  it("highlights serial search, not traceability, on a serial page", () => {
    expect(activeNavHref("/traceability/serials", NAV_ITEMS)).toBe(
      "/traceability/serials",
    );
    expect(activeNavHref("/traceability/serials/SN-1", NAV_ITEMS)).toBe(
      "/traceability/serials",
    );
  });

  it("keeps traceability for a batch trace", () => {
    expect(activeNavHref("/traceability/PB-2026-00001", NAV_ITEMS)).toBe(
      "/traceability",
    );
  });

  it("highlights billing or organisation settings, not my account, on the pages beneath it", () => {
    expect(activeNavHref("/settings/billing", NAV_ITEMS)).toBe(
      "/settings/billing",
    );
    expect(activeNavHref("/settings/organisation", NAV_ITEMS)).toBe(
      "/settings/organisation",
    );
    expect(activeNavHref("/settings", NAV_ITEMS)).toBe("/settings");
  });

  it("does not match a longer word that only starts the same", () => {
    expect(activeNavHref("/materials-x", NAV_ITEMS)).toBeUndefined();
  });
});

describe("forManufacturingModel", () => {
  const manufacturing = visibleNavItems(["MANUFACTURING_VIEW"]).filter((item) =>
    item.href.startsWith("/manufacturing/"),
  );
  const shown = (model?: "DISCRETE" | "PROCESS" | "HYBRID") =>
    forManufacturingModel(manufacturing, model).map((item) => item.label);

  it("shows BOMs to discrete, recipes to process, and both to hybrid organisations", () => {
    expect(shown("DISCRETE")).toContain("BOMs");
    expect(shown("DISCRETE")).not.toContain("Recipes");
    expect(shown("PROCESS")).toEqual([
      "Work centres",
      "Machines",
      "Recipes",
      "Routings",
      "Quality checkpoints",
      "Configuration",
      "Downtime",
    ]);
    expect(shown("HYBRID")).toEqual(
      expect.arrayContaining(["BOMs", "Recipes"]),
    );
  });

  it("is hidden from people without the permission", () => {
    expect(
      visibleNavItems(["NCR_VIEW"]).some((item) =>
        item.href.startsWith("/manufacturing/"),
      ),
    ).toBe(false);
  });

  it("shows plans and work orders to whoever can see work orders, in every model", () => {
    const labels = forManufacturingModel(
      visibleNavItems(["WORK_ORDER_VIEW"]),
      "PROCESS",
    ).map((item) => item.label);
    expect(labels).toEqual(
      expect.arrayContaining(["Production plans", "Work orders"]),
    );
    expect(labels).not.toContain("Machines");
  });
});

describe("menu order", () => {
  it("starts with the dashboard, then puts every other item in a group", () => {
    expect(NAV_ITEMS[0]).toMatchObject({ label: "Dashboard" });
    expect(NAV_ITEMS[0].group).toBeUndefined();
    expect(NAV_ITEMS.slice(1).filter((item) => !item.group)).toEqual([]);
  });

  it("keeps each group together, in the order work flows", () => {
    const sections = navSections(NAV_ITEMS);
    expect(sections.map((section) => section.group)).toEqual([
      undefined,
      ...NAV_GROUPS,
    ]);
    expect(sections.flatMap((section) => section.items)).toEqual(NAV_ITEMS);
  });

  it("follows the workflow within a group", () => {
    const group = (name: string) =>
      NAV_ITEMS.filter((item) => item.group === name).map((item) => item.label);
    expect(group("Incoming material")).toEqual([
      "Purchase orders",
      "Receipts",
      "Inspections",
    ]);
    expect(group("Quality")).toEqual(["NCRs", "CAPAs"]);
    expect(group("Traceability")).toEqual([
      "Traceability",
      "Serial search",
      "Recalls",
      "Corrections",
    ]);
  });

  it("leaves out a group with nothing visible", () => {
    const sections = navSections(visibleNavItems(["NCR_VIEW", "CAPA_VIEW"]));
    expect(sections.map((section) => section.group)).toEqual([
      undefined,
      "Set up",
      "Quality",
      "Administration",
      "Account and help",
    ]);
  });
});
