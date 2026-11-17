import { NextResponse, type NextRequest } from "next/server";
import {
  REFRESH_COOKIE,
  clearSessionCookies,
  isTrustedOrigin,
  refreshTokens,
  setSessionCookies,
  type TokenResponse,
} from "@/lib/bff";

/** Leaves a support session for the operator's own, which their refresh cookie still holds. */
export async function POST(request: NextRequest) {
  if (!isTrustedOrigin(request)) {
    return NextResponse.json(
      { success: false, error: { code: "FORBIDDEN", message: "Cross-site request refused." } },
      { status: 403 },
    );
  }
  const refreshToken = request.cookies.get(REFRESH_COOKIE)?.value;
  const refreshed = refreshToken ? await refreshTokens(refreshToken) : null;
  if (!refreshed?.ok) {
    const signedOut = NextResponse.json({ success: true, data: { signedOut: true } });
    clearSessionCookies(signedOut);
    return signedOut;
  }
  const response = NextResponse.json({ success: true, data: { signedOut: false } });
  setSessionCookies(response, (await refreshed.json()).data as TokenResponse);
  return response;
}
