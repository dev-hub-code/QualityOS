import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { SystemStatus } from "./system-status";

function renderWith(body: unknown) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => Response.json(body)),
  );
  render(
    <QueryClientProvider client={new QueryClient()}>
      <SystemStatus />
    </QueryClientProvider>,
  );
}

describe("SystemStatus", () => {
  it("shows the operational state and version", async () => {
    renderWith({ state: "operational", version: "1.2.3" });

    expect(await screen.findByText("All systems operational")).toBeInTheDocument();
    expect(screen.getByText("Version 1.2.3")).toBeInTheDocument();
  });

  it("shows when the API cannot be reached", async () => {
    renderWith({ state: "unreachable", version: null });

    expect(await screen.findByText("API unreachable")).toBeInTheDocument();
  });

  it("shows a degraded backend", async () => {
    renderWith({ state: "not-ready", version: null });

    expect(await screen.findByText("Starting up or degraded")).toBeInTheDocument();
  });
});
