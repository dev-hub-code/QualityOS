import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { SerialDetail } from "./serial-detail";

function show(body: unknown, status = 200) {
  vi.stubGlobal("fetch", vi.fn(async () => Response.json(body, { status })));
  render(
    <QueryClientProvider client={new QueryClient()}>
      <SerialDetail serial="SN 1" backHref="/traceability/serials?q=SN" />
    </QueryClientProvider>,
  );
}

const TRACE = {
  serialNumber: "SN 1",
  batchNumber: "PB-1",
  productCode: "SHAFT",
  productName: "Drive Shaft",
  batchStatus: "RELEASED",
  manufacturedAt: "2026-10-01T10:00:00Z",
  inspections: [{ id: "i", inspectionNumber: "INS-1", inspectionType: "FQC", status: "COMPLETED", finalResult: "PASS", disposition: "ACCEPT" }],
  deliveries: [
    { dispatchId: "a", dispatchNumber: "DSP-1", customerName: "Tata Motors", dispatchedOn: "2026-10-02", shippedOn: "2026-10-03", returned: true },
    { dispatchId: "b", dispatchNumber: "DSP-2", customerName: "Bajaj Auto", dispatchedOn: "2026-10-09", shippedOn: null, returned: false },
  ],
};

describe("SerialDetail", () => {
  it("shows the unit, its inspections and every customer it reached", async () => {
    show({ success: true, data: TRACE });
    expect(await screen.findByRole("heading", { name: "SN 1" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "PB-1" })).toHaveAttribute("href", "/traceability/PB-1");
    expect(screen.getByRole("link", { name: "INS-1" })).toBeInTheDocument();
    expect(screen.getByText(/Tata Motors on 2026-10-03/)).toBeInTheDocument();
    expect(screen.getByText("returned")).toBeInTheDocument();
    expect(screen.getByText("awaiting shipment")).toBeInTheDocument();
    expect(fetch).toHaveBeenCalledWith(expect.stringContaining("/serials/SN%201"), expect.anything());
  });

  it("keeps the way back when the unit cannot be seen", async () => {
    show({ success: false, error: { code: "RESOURCE_NOT_FOUND", message: "Not found" } }, 404);
    expect(await screen.findByText("This record does not exist.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to search" })).toHaveAttribute("href", "/traceability/serials?q=SN");
  });
});
