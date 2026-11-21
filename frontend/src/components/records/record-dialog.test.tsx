import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { FACILITY_FIELDS } from "@/lib/record-fields";
import { RecordDialog } from "./record-dialog";

const STATES = [
  { gstCode: "24", name: "Gujarat", kind: "STATE" },
  { gstCode: "27", name: "Maharashtra", kind: "STATE" },
];
const CITIES: Record<string, string[]> = { "24": ["Surat", "Vadodara"], "27": ["Mumbai", "Pune"] };

function show(record?: object) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) => {
      const code = /states\/(\d+)\/cities/.exec(String(url))?.[1];
      return Response.json({ success: true, data: code ? CITIES[code] : STATES });
    }),
  );
  const onSubmit = vi.fn(async () => undefined);
  render(
    <QueryClientProvider client={new QueryClient()}>
      <RecordDialog title="Facility" fields={FACILITY_FIELDS} record={record} onSubmit={onSubmit} onClose={vi.fn()} />
    </QueryClientProvider>,
  );
  return onSubmit;
}

const state = () => screen.getByLabelText("State") as HTMLSelectElement;
const city = () => screen.getByLabelText("City") as HTMLSelectElement;
const options = (select: HTMLSelectElement) => within(select).getAllByRole("option").map((option) => option.textContent);
const states = () => waitFor(() => expect(options(state())).toContain("Maharashtra"));
/** Waits for the chosen state's list, which ends in Other. */
const cities = (name: string) =>
  waitFor(() => expect(options(city())).toEqual(expect.arrayContaining([name, "Other…"])));

describe("RecordDialog's state and city", () => {
  it("picks a state, then one of its cities", async () => {
    const onSubmit = show({ code: "P1", name: "Plant" });
    await states();
    expect(state().tagName).toBe("SELECT");
    expect(city()).toBeDisabled();
    fireEvent.change(state(), { target: { value: "Maharashtra" } });
    await cities("Pune");
    expect(options(city())).toEqual(["Select…", "Mumbai", "Pune", "Other…"]);
    fireEvent.change(city(), { target: { value: "Pune" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() =>
      expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ state: "Maharashtra", city: "Pune" })),
    );
  });

  it("clears the city when the state changes", async () => {
    show({ state: "Maharashtra", city: "Pune" });
    await cities("Pune");
    expect(city()).toHaveValue("Pune");
    fireEvent.change(state(), { target: { value: "Gujarat" } });
    await cities("Surat");
    expect(city()).toHaveValue("");
  });

  it("takes a city the list lacks under Other", async () => {
    const onSubmit = show({ code: "P1", name: "Plant", state: "Maharashtra" });
    await cities("Pune");
    fireEvent.change(city(), { target: { value: "__other__" } });
    fireEvent.change(screen.getByLabelText("City, not in the list"), { target: { value: "Shirur" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ city: "Shirur" })));
  });

  it("keeps a saved city the list lacks", async () => {
    show({ state: "Maharashtra", city: "Shirur" });
    await cities("Pune");
    expect(screen.getByLabelText("City, not in the list")).toHaveValue("Shirur");
  });

  it("keeps a saved state the list lacks, with its city typed", async () => {
    show({ state: "Bombay Presidency", city: "Poona" });
    await states();
    expect(state()).toHaveValue("Bombay Presidency");
    expect(options(state())).toContain("Bombay Presidency (not in the list)");
    expect(city()).toHaveValue("Poona");
    expect(city().tagName).toBe("INPUT");
  });

  it("shows a saved state while the list loads", () => {
    show({ state: "Maharashtra" });
    expect(state()).toHaveValue("Maharashtra");
  });
});

describe("RecordDialog's state from GSTIN", () => {
  it("fills an empty state, and follows the GSTIN while it is the one that filled it", async () => {
    show();
    await states();
    fireEvent.change(screen.getByLabelText("GSTIN"), { target: { value: "27AAPFU0939F1ZV" } });
    await waitFor(() => expect(state()).toHaveValue("Maharashtra"));
    fireEvent.change(screen.getByLabelText("GSTIN"), { target: { value: "24AAPFU0939F1ZV" } });
    await waitFor(() => expect(state()).toHaveValue("Gujarat"));
  });

  it("keeps a state picked by hand and says when the GSTIN disagrees", async () => {
    show({ state: "Gujarat" });
    await states();
    fireEvent.change(screen.getByLabelText("GSTIN"), { target: { value: "27AAPFU0939F1ZV" } });
    expect(await screen.findByText("The GSTIN is registered in Maharashtra.")).toBeInTheDocument();
    expect(state()).toHaveValue("Gujarat");
  });
});
