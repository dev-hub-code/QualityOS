import { describe, expect, it } from "vitest";
import {
  describeLimits,
  formatDuration,
  freeQuantity,
  operationActions,
  planActions,
  reportMismatch,
  revisionActions,
  toLineRequest,
  usesKind,
  workOrderActions,
} from "@/lib/manufacturing";

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

describe("freeQuantity", () => {
  it("leaves out what work orders have set aside", () => {
    expect(freeQuantity({ remainingQuantity: "20.000000", reservedQuantity: "7.500000" })).toBe("12.5");
    expect(freeQuantity({ remainingQuantity: "5.000000", reservedQuantity: "5.000000" })).toBe("0");
    expect(freeQuantity({ remainingQuantity: "3" })).toBe("3");
  });
});

describe("operationActions", () => {
  const operator = { id: "me", execute: true, supervise: false };
  const supervisor = { id: "boss", execute: true, supervise: true };
  const base = { status: "READY" as const, workOrderStatus: "IN_PROGRESS" as const, processed: "0", operatorId: null };

  it("lets an operator run what is free or theirs, while the order is in production", () => {
    expect(operationActions(base, operator)).toEqual(["start"]);
    expect(operationActions({ ...base, status: "IN_PROGRESS", operatorId: "me" }, operator)).toEqual([
      "pause",
      "report",
      "measure",
      "complete",
    ]);
    expect(operationActions({ ...base, status: "PAUSED", operatorId: "me" }, operator)).toContain("resume");
    expect(operationActions({ ...base, operatorId: "someone else" }, operator)).toEqual([]);
    expect(operationActions({ ...base, workOrderStatus: "ON_HOLD" }, operator)).toEqual([]);
    expect(operationActions(base, { ...operator, execute: false })).toEqual([]);
  });

  it("gives supervisors assignment, blocking and skipping", () => {
    expect(operationActions({ ...base, operatorId: "someone else" }, supervisor)).toEqual(["start", "assign", "block", "skip"]);
    expect(operationActions({ ...base, status: "BLOCKED", workOrderStatus: "ON_HOLD" }, supervisor)).toEqual([
      "assign",
      "unblock",
      "skip",
    ]);
    expect(operationActions({ ...base, status: "PENDING", workOrderStatus: "RELEASED" }, supervisor)).toEqual(["assign", "skip"]);
    expect(operationActions({ ...base, status: "BLOCKED", processed: "2" }, supervisor)).not.toContain("skip");
    expect(operationActions({ ...base, status: "COMPLETED" }, supervisor)).toEqual([]);
  });
});

describe("formatDuration", () => {
  it("shows minutes, then hours and minutes", () => {
    expect(formatDuration(59)).toBe("0 min");
    expect(formatDuration(25 * 60)).toBe("25 min");
    expect(formatDuration(135 * 60 + 10)).toBe("2 h 15 min");
  });
});

describe("reportMismatch", () => {
  it("accepts a report whose parts add up, and says by how much one does not", () => {
    expect(reportMismatch({ quantity: "10", goodQuantity: "8", rejectedQuantity: "1.5", scrapQuantity: "0.5" })).toBeNull();
    expect(reportMismatch({ quantity: "10", goodQuantity: "8", rejectedQuantity: "1", scrapQuantity: "" })).toBe(
      "Good, rejected and scrap make 9, not 10.",
    );
  });
});
