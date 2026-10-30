import { describe, expect, it, vi } from "vitest";
import { fetchSystemStatus } from "./system-status";

const BACKEND = "http://backend:8080";

function stubBackend(routes: Record<string, Response | Error>) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) => {
      const result = routes[url.replace(BACKEND, "")];
      if (result instanceof Error) throw result;
      return result;
    }),
  );
}

describe("fetchSystemStatus", () => {
  it("reports operational with the version when the backend is ready", async () => {
    stubBackend({
      "/actuator/health/readiness": Response.json({ status: "UP" }),
      "/api/v1/system/info": Response.json({ success: true, data: { version: "1.2.3" } }),
    });

    await expect(fetchSystemStatus(BACKEND)).resolves.toEqual({
      state: "operational",
      version: "1.2.3",
    });
  });

  it("stays operational when only the version lookup fails", async () => {
    stubBackend({
      "/actuator/health/readiness": Response.json({ status: "UP" }),
      "/api/v1/system/info": new Response(null, { status: 500 }),
    });

    await expect(fetchSystemStatus(BACKEND)).resolves.toEqual({
      state: "operational",
      version: null,
    });
  });

  it("reports not-ready when readiness returns 503", async () => {
    stubBackend({
      "/actuator/health/readiness": Response.json({ status: "DOWN" }, { status: 503 }),
    });

    await expect(fetchSystemStatus(BACKEND)).resolves.toEqual({
      state: "not-ready",
      version: null,
    });
  });

  it("reports unreachable when the connection fails", async () => {
    stubBackend({ "/actuator/health/readiness": new TypeError("fetch failed") });

    await expect(fetchSystemStatus(BACKEND)).resolves.toEqual({
      state: "unreachable",
      version: null,
    });
  });
});
