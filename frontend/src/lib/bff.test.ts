import { NextRequest } from "next/server";
import { describe, expect, it } from "vitest";
import { forwardedHeaders, isProxyablePath, isTokenIssuingRequest, isTrustedOrigin, returnedHeaders } from "./bff";

function request(method: string, headers: Record<string, string>) {
  return new NextRequest("http://app.test/api/v1/users", { method, headers });
}

describe("isProxyablePath", () => {
  it("passes email-link endpoints but never one that issues a session", () => {
    expect(isProxyablePath(["auth", "password-reset", "confirm"])).toBe(true);
    expect(isProxyablePath(["auth", "invitations", "preview"])).toBe(true);
    expect(isProxyablePath(["auth", "invitations", "accept"])).toBe(false);
    expect(isProxyablePath(["auth", "login"])).toBe(false);
  });

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

describe("forwardedHeaders", () => {
  it("keeps only the address added by the proxy", () => {
    const headers = forwardedHeaders(request("POST", { "x-forwarded-for": "198.51.100.7, 203.0.113.9" }));
    expect(headers.get("x-forwarded-for")).toBe("203.0.113.9");
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

describe("returnedHeaders", () => {
  it("keeps the headers that make downloads safe, and nothing else", () => {
    const upstream = new Response("x", {
      headers: {
        "content-type": "application/pdf",
        "content-disposition": "attachment; filename=cert.pdf",
        "x-content-type-options": "nosniff",
        "set-cookie": "leak=1",
      },
    });

    const headers = returnedHeaders(upstream);

    expect(headers.get("content-disposition")).toContain("attachment");
    expect(headers.get("x-content-type-options")).toBe("nosniff");
    expect(headers.get("set-cookie")).toBeNull();
  });
});
