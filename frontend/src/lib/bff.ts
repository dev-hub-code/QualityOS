import type { NextRequest, NextResponse } from "next/server";

/** Server-side helpers for the backend-for-frontend routes. Tokens never reach browser JavaScript. */

export const ACCESS_COOKIE = "qos_access";
export const REFRESH_COOKIE = "qos_refresh";

const REFRESH_MAX_AGE_SECONDS = 14 * 24 * 60 * 60;

export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  expiresInSeconds: number;
  tenantId: string;
}

export function backendUrl(): string {
  return process.env.BACKEND_URL ?? "http://localhost:8080";
}

function cookieOptions() {
  return {
    httpOnly: true,
    sameSite: "lax" as const,
    path: "/",
    secure: process.env.COOKIE_SECURE !== "false",
  };
}

export function setSessionCookies(response: NextResponse, tokens: TokenResponse) {
  response.cookies.set(ACCESS_COOKIE, tokens.accessToken, {
    ...cookieOptions(),
    maxAge: tokens.expiresInSeconds,
  });
  response.cookies.set(REFRESH_COOKIE, tokens.refreshToken, {
    ...cookieOptions(),
    maxAge: REFRESH_MAX_AGE_SECONDS,
  });
}

export function clearSessionCookies(response: NextResponse) {
  response.cookies.delete(ACCESS_COOKIE);
  response.cookies.delete(REFRESH_COOKIE);
}

/** Headers carried from the browser request to the backend. */
export function forwardedHeaders(request: NextRequest): Headers {
  const headers = new Headers();
  for (const name of ["content-type", "accept", "x-correlation-id"]) {
    const value = request.headers.get(name);
    if (value) headers.set(name, value);
  }
  const clientAddress = request.headers.get("x-forwarded-for");
  if (clientAddress) headers.set("x-forwarded-for", clientAddress);
  return headers;
}

/** Headers carried from the backend's response to the browser. Downloads depend on the last two. */
export function returnedHeaders(upstream: Response): Headers {
  const headers = new Headers({
    "content-type": upstream.headers.get("content-type") ?? "application/json",
  });
  for (const name of ["x-correlation-id", "content-disposition", "x-content-type-options"]) {
    const value = upstream.headers.get(name);
    if (value) headers.set(name, value);
  }
  return headers;
}

/**
 * Cookies alone authenticate a request, so a state-changing request must prove it came from this site.
 * SameSite=Lax already blocks most cross-site posts; this also covers same-site subdomains.
 */
export function isTrustedOrigin(request: NextRequest): boolean {
  if (["GET", "HEAD"].includes(request.method)) return true;
  const origin = request.headers.get("origin");
  if (!origin) return false;
  const host = request.headers.get("x-forwarded-host") ?? request.headers.get("host");
  try {
    return new URL(origin).host === host;
  } catch {
    return false;
  }
}

export function refreshTokens(refreshToken: string): Promise<Response> {
  return fetch(`${backendUrl()}/api/v1/auth/refresh`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ refreshToken }),
  });
}

const PROXYABLE_AUTH_PATHS = new Set([
  "me",
  "change-password",
  "password-reset/request",
  "password-reset/confirm",
  "email-verification/request",
  "email-verification/confirm",
  "invitations/preview",
]);

/**
 * Endpoints that return session tokens have dedicated routes, so that those tokens stay out of the browser.
 * The one-time tokens from email links are in the page's address already, and pass through here.
 */
export function isProxyablePath(segments: string[]): boolean {
  if (segments.some((segment) => segment === ".." || segment === ".")) return false;
  if (segments[0] !== "auth") return true;
  return PROXYABLE_AUTH_PATHS.has(segments.slice(1).join("/"));
}

export function isTokenIssuingRequest(method: string, segments: string[]): boolean {
  return method === "POST" && segments.length === 1 && segments[0] === "organizations";
}
