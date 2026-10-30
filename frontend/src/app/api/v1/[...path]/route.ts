import { NextResponse, type NextRequest } from "next/server";
import {
  ACCESS_COOKIE,
  REFRESH_COOKIE,
  backendUrl,
  clearSessionCookies,
  forwardedHeaders,
  isProxyablePath,
  isTokenIssuingRequest,
  isTrustedOrigin,
  refreshTokens,
  setSessionCookies,
  type TokenResponse,
} from "@/lib/bff";

function errorResponse(status: number, code: string, message: string) {
  return NextResponse.json({ success: false, error: { code, message } }, { status });
}

async function forward(
  request: NextRequest,
  context: RouteContext<"/api/v1/[...path]">,
): Promise<NextResponse> {
  const { path } = await context.params;
  if (!isProxyablePath(path) || isTokenIssuingRequest(request.method, path)) {
    return errorResponse(404, "RESOURCE_NOT_FOUND", "Not Found");
  }
  if (!isTrustedOrigin(request)) {
    return errorResponse(403, "FORBIDDEN", "Cross-site request refused.");
  }

  const target =
    `${backendUrl()}/api/v1/${path.map(encodeURIComponent).join("/")}${request.nextUrl.search}`;
  const body = ["GET", "HEAD"].includes(request.method) ? undefined : await request.arrayBuffer();
  const send = (accessToken: string | undefined) => {
    const headers = forwardedHeaders(request);
    if (accessToken) headers.set("authorization", `Bearer ${accessToken}`);
    return fetch(target, { method: request.method, headers, body });
  };

  let renewed: TokenResponse | undefined;
  let upstream = await send(request.cookies.get(ACCESS_COOKIE)?.value);
  const refreshToken = request.cookies.get(REFRESH_COOKIE)?.value;

  if (upstream.status === 401 && refreshToken) {
    const refreshed = await refreshTokens(refreshToken);
    if (refreshed.ok) {
      renewed = (await refreshed.json()).data as TokenResponse;
      upstream = await send(renewed.accessToken);
    }
  }

  const hasBody = upstream.status !== 204 && upstream.status !== 304;
  const response = new NextResponse(hasBody ? await upstream.arrayBuffer() : null, {
    status: upstream.status,
    headers: { "content-type": upstream.headers.get("content-type") ?? "application/json" },
  });
  const correlationId = upstream.headers.get("x-correlation-id");
  if (correlationId) response.headers.set("x-correlation-id", correlationId);

  if (renewed) setSessionCookies(response, renewed);
  if (upstream.status === 401) clearSessionCookies(response);
  return response;
}

export {
  forward as GET,
  forward as POST,
  forward as PUT,
  forward as PATCH,
  forward as DELETE,
};
