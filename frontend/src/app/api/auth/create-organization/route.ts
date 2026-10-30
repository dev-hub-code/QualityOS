import type { NextRequest } from "next/server";
import { issueSessionVia } from "@/lib/auth-route";

export function POST(request: NextRequest) {
  return issueSessionVia(request, "organizations");
}
