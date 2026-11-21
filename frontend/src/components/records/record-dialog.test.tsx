import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { FACILITY_FIELDS } from "@/lib/record-fields";
import { RecordDialog } from "./record-dialog";

const STATES = [
  { gstCode: "24", name: "Gujarat", kind: "STATE" },
  { gstCode: "27", name: "Maharashtra", kind: "STATE" },
];

function show(record?: object) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) =>
      Response.json({ success: true, data: String(url).includes("/cities") ? ["Pune"] : STATES }),
    ),
  );
  render(
    <QueryClientProvider client={new QueryClient()}>
      <RecordDialog title="Facility" fields={FACILITY_FIELDS} record={record} onSubmit={vi.fn()} onClose={vi.fn()} />
    </QueryClientProvider>,
  );
  // The states have arrived once they are suggested.
  return waitFor(() => expect(document.querySelectorAll("#field-state-suggestions option")).toHaveLength(2));
}

const type = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });

describe("RecordDialog's state from GSTIN", () => {
  it("fills an empty state, and follows the GSTIN while it is the one that filled it", async () => {
    await show();
    type("GSTIN", "27AAPFU0939F1ZV");
    await waitFor(() => expect(screen.getByLabelText("State")).toHaveValue("Maharashtra"));
    type("GSTIN", "24AAPFU0939F1ZV");
    await waitFor(() => expect(screen.getByLabelText("State")).toHaveValue("Gujarat"));
  });

  it("keeps a state typed by hand and says when the GSTIN disagrees", async () => {
    await show({ state: "Gujarat" });
    type("GSTIN", "27AAPFU0939F1ZV");
    expect(await screen.findByText("The GSTIN is registered in Maharashtra.")).toBeInTheDocument();
    expect(screen.getByLabelText("State")).toHaveValue("Gujarat");
  });
});
