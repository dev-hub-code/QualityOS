import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { SerialSearch } from "./serial-search";

const replace = vi.fn();
let search = "";
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace }),
  useSearchParams: () => new URLSearchParams(search),
}));

const UNIT = {
  serialNumber: "SN-1",
  productCode: "SHAFT",
  productName: "Drive Shaft",
  batchNumber: "PB-1",
  facilityName: "Pune",
  manufacturedAt: "2026-10-01T10:00:00Z",
  inspectionStatus: "PASS",
  qualityStatus: "RELEASED",
  dispatchStatus: "SHIPPED",
  customerName: "Tata Motors",
  dispatchNumber: "DSP-1",
  dispatchedOn: "2026-10-02",
};

function respond(data: unknown, status = 200) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) =>
      String(url).includes("/traceability/serials")
        ? Response.json(status === 200 ? { success: true, data } : { success: false, error: { code: "X", message: "It broke" } }, { status })
        : Response.json({ success: true, data: { items: [], page: 0, size: 100, totalItems: 0, totalPages: 0 } }),
    ),
  );
}

function show() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <SerialSearch />
    </QueryClientProvider>,
  );
}

const page = (items: unknown[], totalPages = 1) => ({ items, page: 0, size: 25, totalItems: items.length, totalPages });

describe("SerialSearch", () => {
  beforeEach(() => {
    replace.mockClear();
    search = "";
  });

  it("asks for a search before listing anything", () => {
    respond(page([]));
    show();
    expect(screen.getByText(/Enter a serial number/)).toBeInTheDocument();
    expect(fetch).not.toHaveBeenCalledWith(expect.stringContaining("/traceability/serials"), expect.anything());
  });

  it("puts the search in the URL", async () => {
    respond(page([]));
    show();
    fireEvent.change(screen.getByLabelText("Search"), { target: { value: " SN-1 " } });
    fireEvent.click(screen.getByRole("button", { name: "Search" }));
    expect(replace).toHaveBeenCalledWith("/traceability/serials?q=SN-1");
  });

  it("shows each result, linked to its detail page with a way back", async () => {
    search = "q=SN";
    respond(page([UNIT]));
    show();
    const link = await screen.findByRole("link", { name: "SN-1" });
    expect(link).toHaveAttribute("href", "/traceability/serials/SN-1?from=q%3DSN");
    expect(screen.getByText("Tata Motors")).toBeInTheDocument();
    expect(screen.getByText("released")).toBeInTheDocument();
  });

  it("says when nothing matches", async () => {
    search = "q=SN";
    respond(page([]));
    show();
    expect(await screen.findByText("No serial numbers match.")).toBeInTheDocument();
  });

  it("shows a server error", async () => {
    search = "q=SN";
    respond(null, 500);
    show();
    expect(await screen.findByText("It broke")).toBeInTheDocument();
  });

  it("pages through results in the URL", async () => {
    search = "q=SN";
    respond({ ...page([UNIT], 2), totalItems: 30 });
    show();
    fireEvent.click(await screen.findByRole("button", { name: "Next" }));
    expect(replace).toHaveBeenCalledWith("/traceability/serials?q=SN&page=1");
  });
});
