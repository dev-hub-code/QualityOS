import { describe, expect, it } from "vitest";
import { organizationSchema, registerSchema } from "./schemas";

describe("registerSchema", () => {
  const valid = {
    fullName: "Asha Rao",
    email: "asha@example.com",
    password: "long-enough-pass",
    organizationName: "Acme",
  };

  it("accepts valid input", () => {
    expect(registerSchema.safeParse(valid).success).toBe(true);
  });

  it("matches the backend's minimum password length", () => {
    expect(registerSchema.safeParse({ ...valid, password: "too-short" }).success).toBe(false);
  });
});

describe("organizationSchema", () => {
  const valid = {
    name: "Acme",
    legalName: "",
    gstin: "",
    industry: "",
    address: "",
    contactPhone: "",
    timezone: "Asia/Kolkata",
    currency: "inr",
  };

  it("allows an empty GSTIN and normalises case", () => {
    const result = organizationSchema.parse({ ...valid, gstin: "27abcde1234f1z5" });

    expect(result.gstin).toBe("27ABCDE1234F1Z5");
    expect(result.currency).toBe("INR");
  });

  it("rejects a malformed GSTIN", () => {
    expect(organizationSchema.safeParse({ ...valid, gstin: "ABC" }).success).toBe(false);
  });

  it("takes a phone number with a country code, or none", () => {
    expect(organizationSchema.safeParse({ ...valid, contactPhone: "+91 20 1234 5678" }).success).toBe(true);
    expect(organizationSchema.safeParse({ ...valid, contactPhone: "call me" }).success).toBe(false);
  });
});
