import { NextResponse, type NextRequest } from "next/server";
import {
  ACCESS_COOKIE,
  REFRESH_COOKIE,
  backendUrl,
  clearSessionCookies,
  forwardedHeaders,
  isEventStream,
  isProxyablePath,
  isTokenIssuingRequest,
  isTrustedOrigin,
  refreshTokens,
  returnedHeaders,
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
    // A browser that leaves closes the backend connection too, which matters for an event stream.
    return fetch(target, { method: request.method, headers, body, signal: request.signal });
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
  // Events are passed on as they come; anything else is read whole.
  const streaming = upstream.ok && isEventStream(upstream);
  const response = new NextResponse(streaming ? upstream.body : hasBody ? await upstream.arrayBuffer() : null, {
    status: upstream.status,
    headers: returnedHeaders(upstream),
  });
  if (streaming) {
    response.headers.set("cache-control", "no-cache, no-transform");
    response.headers.set("x-accel-buffering", "no");
  }

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
