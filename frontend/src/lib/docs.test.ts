import { describe, expect, it } from "vitest";
import { visibleNavItems } from "./navigation";
import { DOC_EXAMPLES } from "./doc-examples";
import { DOC_FLOWS } from "./doc-flows";
import { DOC_PAGES } from "./docs";

const LABELS: Record<string, string> = { "Bell icon": "" };

describe("documentation", () => {
  it("gives every topic a unique address and its sections", () => {
    expect(new Set(DOC_PAGES.map((page) => page.slug)).size).toBe(DOC_PAGES.length);
    for (const page of DOC_PAGES) {
      expect(page.sections.length).toBeGreaterThan(0);
    }
  });

  it("gives every topic worked examples that explain each step", () => {
    expect(Object.keys(DOC_EXAMPLES).sort()).toEqual(DOC_PAGES.map((page) => page.slug).sort());
    for (const [slug, examples] of Object.entries(DOC_EXAMPLES)) {
      expect(examples.length, slug).toBeGreaterThan(0);
      expect(new Set(examples.map((example) => example.title)).size, slug).toBe(examples.length);
      for (const example of examples) {
        expect(example.scenario.length, example.title).toBeGreaterThan(40);
        expect(example.steps.length, example.title).toBeGreaterThan(1);
        expect(example.takeaways.length, example.title).toBeGreaterThan(0);
        expect(new Set(example.steps.map((step) => step.action)).size, example.title).toBe(example.steps.length);
        for (const step of example.steps) {
          for (const text of [step.who, step.action, step.result, step.why]) {
            expect(text.trim().length, `${example.title}: ${step.action}`).toBeGreaterThan(0);
          }
        }
      }
    }
  });

  it("gives every topic a flow chart, and no chart without a topic", () => {
    expect(Object.keys(DOC_FLOWS).sort()).toEqual(DOC_PAGES.map((page) => page.slug).sort());
    for (const [slug, flows] of Object.entries(DOC_FLOWS)) {
      expect(flows.length, slug).toBeGreaterThan(0);
      expect(new Set(flows.map((flow) => flow.title)).size, slug).toBe(flows.length);
      for (const flow of flows) {
        expect(flow.steps.length, flow.title).toBeGreaterThan(1);
        expect(new Set(flow.steps.map((step) => step.label)).size, flow.title).toBe(flow.steps.length);
        for (const step of flow.steps) {
          if (step.next) expect(step.branches?.length, `${flow.title}: ${step.label}`).toBeGreaterThan(0);
        }
      }
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
    const text = JSON.stringify([DOC_PAGES, DOC_FLOWS, DOC_EXAMPLES]);
    expect(text).not.toMatch(/₹|\bRs\b|\bINR\b/);
    expect(text).not.toMatch(/\b(compliant|certified)\b/i);
  });
});

function permissions() {
  return ["SUPPLIER", "PRODUCT", "PURCHASE", "INSPECTION", "TEMPLATE", "NCR", "CAPA", "TRACEABILITY", "RECALL",
    "CALIBRATION", "AUDIT", "REPORT", "FACILITY", "MANUFACTURING", "WORK_ORDER", "PRODUCTION_ANALYTICS"]
    .map((name) => `${name}_VIEW`)
    .concat("PRODUCTION_EXECUTE");
}
