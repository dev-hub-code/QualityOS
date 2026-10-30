import type { NextRequest } from "next/server";
import { issueSessionVia } from "@/lib/auth-route";
import { REFRESH_COOKIE } from "@/lib/bff";

export function POST(request: NextRequest) {
  return issueSessionVia(request, "auth/switch-tenant", (body) => ({
    tenantId: body.tenantId,
    refreshToken: request.cookies.get(REFRESH_COOKIE)?.value,
  }));
}
