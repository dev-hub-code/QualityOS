import { NextRequest } from "next/server";
import { describe, expect, it } from "vitest";
import { isProxyablePath, isTokenIssuingRequest, isTrustedOrigin } from "./bff";

function request(method: string, headers: Record<string, string>) {
  return new NextRequest("http://app.test/api/v1/users", { method, headers });
}

describe("isProxyablePath", () => {
  it("allows business endpoints", () => {
    expect(isProxyablePath(["users"])).toBe(true);
    expect(isProxyablePath(["users", "123", "roles"])).toBe(true);
  });

  it("allows only auth endpoints that do not carry tokens", () => {
    expect(isProxyablePath(["auth", "me"])).toBe(true);
    expect(isProxyablePath(["auth", "change-password"])).toBe(true);
    for (const path of ["login", "register", "refresh", "logout", "switch-tenant"]) {
      expect(isProxyablePath(["auth", path])).toBe(false);
    }
  });

  it("refuses path traversal", () => {
    expect(isProxyablePath(["..", "..", "actuator", "health"])).toBe(false);
  });
});

describe("isTokenIssuingRequest", () => {
  it("catches organisation creation only", () => {
    expect(isTokenIssuingRequest("POST", ["organizations"])).toBe(true);
    expect(isTokenIssuingRequest("GET", ["organizations"])).toBe(false);
    expect(isTokenIssuingRequest("PUT", ["organizations", "1"])).toBe(false);
  });
});

describe("isTrustedOrigin", () => {
  it("allows safe methods without an origin", () => {
    expect(isTrustedOrigin(request("GET", {}))).toBe(true);
  });

  it("allows same-origin writes", () => {
    expect(isTrustedOrigin(request("POST", { origin: "http://app.test", host: "app.test" }))).toBe(true);
  });

  it("refuses cross-site, missing and malformed origins", () => {
    expect(isTrustedOrigin(request("POST", { origin: "http://evil.test", host: "app.test" }))).toBe(false);
    expect(isTrustedOrigin(request("POST", { host: "app.test" }))).toBe(false);
    expect(isTrustedOrigin(request("POST", { origin: "null", host: "app.test" }))).toBe(false);
  });
});
