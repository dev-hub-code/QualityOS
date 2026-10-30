import { NextResponse, type NextRequest } from "next/server";
import { REFRESH_COOKIE, backendUrl, clearSessionCookies, isTrustedOrigin } from "@/lib/bff";

export async function POST(request: NextRequest) {
  if (!isTrustedOrigin(request)) {
    return new NextResponse(null, { status: 403 });
  }
  const refreshToken = request.cookies.get(REFRESH_COOKIE)?.value;
  if (refreshToken) {
    await fetch(`${backendUrl()}/api/v1/auth/logout`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ refreshToken }),
    });
  }
  const response = new NextResponse(null, { status: 204 });
  clearSessionCookies(response);
  return response;
}
