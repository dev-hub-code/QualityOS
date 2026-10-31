import { describe, expect, it } from "vitest";
import { visibleNavItems } from "./navigation";
import { DOC_PAGES } from "./docs";

const LABELS: Record<string, string> = { "Bell icon": "" };

describe("documentation", () => {
  it("gives every topic a unique address and a worked example", () => {
    expect(new Set(DOC_PAGES.map((page) => page.slug)).size).toBe(DOC_PAGES.length);
    for (const page of DOC_PAGES) {
      expect(page.sections.length).toBeGreaterThan(0);
      expect(page.example.steps.length).toBeGreaterThan(0);
    }
  });

  it("names menu items that exist", () => {
    const everything = visibleNavItems(["PLATFORM_ADMIN", "SUBSCRIPTION_MANAGE", "USER_MANAGE", ...permissions()]);
    const labels = new Set(everything.map((item) => item.label));
    for (const page of DOC_PAGES) {
      for (const label of page.menu.split(", ")) {
        expect(label in LABELS || labels.has(label), `${page.slug}: ${label}`).toBe(true);
      }
    }
  });

  it("states no prices and claims no certification", () => {
    const text = JSON.stringify(DOC_PAGES);
    expect(text).not.toMatch(/₹|\bRs\b|\bINR\b/);
    expect(text).not.toMatch(/\b(compliant|certified)\b/i);
  });
});

function permissions() {
  return ["SUPPLIER", "PRODUCT", "PURCHASE", "INSPECTION", "TEMPLATE", "NCR", "CAPA", "TRACEABILITY", "RECALL",
    "CALIBRATION", "AUDIT", "REPORT", "FACILITY"].map((name) => `${name}_VIEW`);
}
