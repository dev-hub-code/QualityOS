import type { NextRequest } from "next/server";
import { afterEach, describe, expect, it, vi } from "vitest";
import { POST } from "./route";

afterEach(() => vi.unstubAllGlobals());

describe("Cashfree webhook", () => {
  it("forwards the exact bytes and signature, with no origin needed", async () => {
    const backend = vi.fn().mockResolvedValue(new Response(null, { status: 401 }));
    vi.stubGlobal("fetch", backend);
    const body = '{"payment_amount":170.00}';

    const response = await POST(
      new Request("http://localhost/api/webhooks/cashfree", {
        method: "POST",
        body,
        headers: { "x-webhook-timestamp": "1", "x-webhook-signature": "abc", cookie: "qos_access=secret" },
      }) as unknown as NextRequest,
    );

    expect(response.status).toBe(401);
    const [url, init] = backend.mock.calls[0];
    expect(url).toMatch(/\/api\/v1\/public\/webhooks\/cashfree$/);
    expect(new TextDecoder().decode(init.body)).toBe(body);
    expect(init.headers.get("x-webhook-signature")).toBe("abc");
    expect(init.headers.get("cookie")).toBeNull();
  });
});
