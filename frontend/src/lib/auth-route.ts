import { NextResponse, type NextRequest } from "next/server";
import {
  ACCESS_COOKIE,
  backendUrl,
  clearChallengeCookie,
  forwardedHeaders,
  isTrustedOrigin,
  setChallengeCookie,
  setPendingVerificationCookie,
  setSessionCookies,
  type TokenResponse,
} from "@/lib/bff";

/** Calls a token-issuing backend endpoint and converts its tokens to cookies. */
export async function issueSessionVia(
  request: NextRequest,
  backendPath: string,
  buildBody: (body: Record<string, unknown>) => unknown = (body) => body,
): Promise<NextResponse> {
  if (!isTrustedOrigin(request)) {
    return NextResponse.json(
      { success: false, error: { code: "FORBIDDEN", message: "Cross-site request refused." } },
      { status: 403 },
    );
  }
  const headers = forwardedHeaders(request);
  headers.set("content-type", "application/json");
  const accessToken = request.cookies.get(ACCESS_COOKIE)?.value;
  if (accessToken) headers.set("authorization", `Bearer ${accessToken}`);

  const upstream = await fetch(`${backendUrl()}/api/v1/${backendPath}`, {
    method: "POST",
    headers,
    body: JSON.stringify(buildBody(await request.json())),
  });
  const payload = await upstream.json();
  if (!upstream.ok) {
    return NextResponse.json(payload, { status: upstream.status });
  }
  // Accepted: either the account has to confirm its email address first, or an operator still has to enter the
  // code from their authenticator app. Either way, any token stays out of the browser's reach.
  if (upstream.status === 202) {
    if (payload.data?.challengeToken) {
      const challenge = NextResponse.json(
        { success: true, data: { secondFactorRequired: true } },
        { status: 202 },
      );
      setChallengeCookie(challenge, payload.data);
      return challenge;
    }
    const pending = NextResponse.json(
      { success: true, data: { verificationRequired: true } },
      { status: 202 },
    );
    if (payload.data?.accessToken) setPendingVerificationCookie(pending, payload.data);
    return pending;
  }
  const response = NextResponse.json({ success: true });
  setSessionCookies(response, payload.data as TokenResponse);
  clearChallengeCookie(response);
  return response;
}
