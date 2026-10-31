import { describe, expect, it } from "vitest";
import type { Subscription } from "@/lib/types";
import { formatMoney, subscriptionNotice } from "./subscription-banner";

const NOW = new Date("2026-10-31T00:00:00Z").getTime();
const subscription = (state: Subscription["state"], endsAt: string): Subscription => ({
  planCode: "GROWTH",
  planName: "Growth",
  state,
  billingCycle: "MONTHLY",
  endsAt,
  readOnlyFrom: "2026-11-20T00:00:00Z",
  cancelledAt: null,
  users: 1,
  maxUsers: 5,
  facilities: 1,
  maxFacilities: 1,
  features: [], storageBytes: 0, maxStorageMb: null, inspectionsThisMonth: 0, maxMonthlyInspections: null, attachments: 0, maxAttachments: null,
});

describe("subscriptionNotice", () => {
  it("stays quiet while the end is far off", () => {
    expect(subscriptionNotice(subscription("ACTIVE", "2026-12-31T00:00:00Z"), NOW)).toBeNull();
  });

  it("warns in the last week", () => {
    expect(subscriptionNotice(subscription("TRIAL", "2026-11-03T00:00:00Z"), NOW)).toContain("trial ends");
  });

  it("says when a lapsed organisation turns read-only", () => {
    expect(subscriptionNotice(subscription("GRACE", "2026-10-30T00:00:00Z"), NOW)).toContain("20 Nov 2026");
  });

  it("promises that records are safe once read-only", () => {
    expect(subscriptionNotice(subscription("READ_ONLY", "2026-09-30T00:00:00Z"), NOW)).toContain("records are safe");
  });
});

describe("formatMoney", () => {
  it("groups Indian style", () => {
    expect(formatMoney(100000)).toBe("₹1,00,000.00");
  });
});
