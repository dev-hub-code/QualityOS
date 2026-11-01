import type { NextRequest } from "next/server";
import { backendUrl } from "@/lib/bff";

const SIGNED_HEADERS = ["x-webhook-timestamp", "x-webhook-signature"];

/** Passes Cashfree's bytes on untouched, because the signature covers them. The backend checks it. */
export async function POST(request: NextRequest) {
  const headers = new Headers({ "content-type": "application/json" });
  for (const name of SIGNED_HEADERS) {
    const value = request.headers.get(name);
    if (value) headers.set(name, value);
  }
  const upstream = await fetch(`${backendUrl()}/api/v1/public/webhooks/cashfree`, {
    method: "POST",
    headers,
    body: await request.arrayBuffer(),
  });
  return new Response(null, { status: upstream.status });
}
