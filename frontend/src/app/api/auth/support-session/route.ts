import { NextResponse, type NextRequest } from "next/server";
import { ACCESS_COOKIE, backendUrl, forwardedHeaders, isTrustedOrigin, setSupportSessionCookie } from "@/lib/bff";

/**
 * Opens a support session in an organisation that granted support access. The session's token replaces the
 * access cookie only; the operator's own refresh cookie stays, so their own session returns when this one ends.
 */
export async function POST(request: NextRequest) {
  if (!isTrustedOrigin(request)) {
    return NextResponse.json(
      { success: false, error: { code: "FORBIDDEN", message: "Cross-site request refused." } },
      { status: 403 },
    );
  }
  const body = (await request.json().catch(() => ({}))) as { tenantId?: unknown; reason?: unknown };
  const tenantId = typeof body.tenantId === "string" && /^[0-9a-f-]{36}$/i.test(body.tenantId) ? body.tenantId : null;
  if (!tenantId) {
    return NextResponse.json(
      { success: false, error: { code: "VALIDATION_ERROR", message: "Choose an organisation." } },
      { status: 400 },
    );
  }
  const headers = forwardedHeaders(request);
  headers.set("content-type", "application/json");
  const accessToken = request.cookies.get(ACCESS_COOKIE)?.value;
  if (accessToken) headers.set("authorization", `Bearer ${accessToken}`);
  const upstream = await fetch(`${backendUrl()}/api/v1/platform/organisations/${tenantId}/support-session`, {
    method: "POST",
    headers,
    body: JSON.stringify({ reason: body.reason }),
  });
  const payload = await upstream.json().catch(() => null);
  if (!upstream.ok) {
    return NextResponse.json(payload, { status: upstream.status });
  }
  const response = NextResponse.json({ success: true, data: { expiresAt: payload.data.expiresAt } });
  setSupportSessionCookie(response, payload.data);
  return response;
}
