import { describe, expect, it } from "vitest";
import { describeLimits, planActions, revisionActions, toLineRequest, usesKind, workOrderActions } from "@/lib/manufacturing";

describe("usesKind", () => {
  it("offers BOMs to discrete and recipes to process organisations, and both to hybrid ones", () => {
    expect(usesKind("DISCRETE", "BOM")).toBe(true);
    expect(usesKind("DISCRETE", "RECIPE")).toBe(false);
    expect(usesKind("PROCESS", "RECIPE")).toBe(true);
    expect(usesKind("HYBRID", "BOM")).toBe(true);
    expect(usesKind("HYBRID", "RECIPE")).toBe(true);
    expect(usesKind(undefined, "BOM")).toBe(false);
  });
});

describe("revisionActions", () => {
  const all = { manage: true, approve: true };

  it("follows the revision's stage", () => {
    expect(revisionActions("DRAFT", false, all)).toEqual(["edit", "submit", "discard"]);
    expect(revisionActions("DRAFT", true, all)).toEqual(["edit", "submit"]);
    expect(revisionActions("SUBMITTED", true, all)).toEqual(["sendBack", "approve"]);
    expect(revisionActions("APPROVED", true, all)).toEqual(["retire"]);
    expect(revisionActions("OBSOLETE", true, all)).toEqual([]);
  });

  it("offers only what the person's permissions allow", () => {
    expect(revisionActions("SUBMITTED", true, { manage: true, approve: false })).toEqual([]);
    expect(revisionActions("DRAFT", false, { manage: false, approve: true })).toEqual([]);
  });
});

describe("toLineRequest", () => {
  it("splits the chosen component into a material or a product", () => {
    expect(toLineRequest({ component: "material:m1", quantity: "2", uomId: "u", scrapPercent: "", fixed: false })).toEqual({
      materialId: "m1",
      componentProductId: null,
      quantity: "2",
      uomId: "u",
      scrapPercent: null,
      scales: true,
      notes: null,
    });
    const product = toLineRequest({ component: "product:p1", quantity: "1", uomId: "u", scrapPercent: "5", fixed: true });
    expect(product.componentProductId).toBe("p1");
    expect(product.scrapPercent).toBe("5");
    expect(product.scales).toBe(false);
  });
});

describe("describeLimits", () => {
  it("reads as a short sentence", () => {
    expect(describeLimits({ target: "180.000000", minimum: "175", maximum: "185", unit: "°C" })).toBe(
      "Target 180, 175 to 185 °C",
    );
    expect(describeLimits({ target: null, minimum: "800", maximum: null, unit: null })).toBe("At least 800");
  });
});

describe("workOrderActions", () => {
  const everyone = { manage: true, approve: true, release: true };
  const order = (status: Parameters<typeof workOrderActions>[0]["status"], heldFrom: Parameters<typeof workOrderActions>[0]["heldFrom"] = null) => ({
    status,
    heldFrom,
  });

  it("offers each stage's next step only", () => {
    expect(workOrderActions(order("DRAFT"), everyone)).toEqual(["edit", "submit", "cancel"]);
    expect(workOrderActions(order("SUBMITTED"), everyone)).toEqual(["approve", "sendBack", "cancel"]);
    expect(workOrderActions(order("APPROVED"), everyone)).toEqual(["release", "hold", "cancel"]);
    expect(workOrderActions(order("RELEASED"), everyone)).toEqual(["start", "hold", "cancel"]);
    expect(workOrderActions(order("IN_PROGRESS"), everyone)).toEqual(["hold"]);
    expect(workOrderActions(order("CANCELLED"), everyone)).toEqual([]);
  });

  it("keeps cancelling a committed order to approvers, and never once production started", () => {
    const planner = { manage: true, approve: false, release: false };
    expect(workOrderActions(order("SUBMITTED"), planner)).toEqual(["cancel"]);
    expect(workOrderActions(order("APPROVED"), planner)).toEqual([]);
    expect(workOrderActions(order("ON_HOLD", "RELEASED"), everyone)).toEqual(["resume", "cancel"]);
    expect(workOrderActions(order("ON_HOLD", "IN_PROGRESS"), everyone)).toEqual(["resume"]);
  });
});

describe("planActions", () => {
  it("edits drafts, raises orders from approved plans, and closes or cancels only open ones", () => {
    const all = { manage: true, approve: true, order: true };
    expect(planActions("DRAFT", all)).toEqual(["edit", "approve", "cancel"]);
    expect(planActions("APPROVED", all)).toEqual(["order", "close", "cancel"]);
    expect(planActions("CLOSED", all)).toEqual([]);
    expect(planActions("APPROVED", { manage: true, approve: false, order: false })).toEqual([]);
  });
});
