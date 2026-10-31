import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { RecallContact } from "@/lib/types";
import { ContactTable, attemptMoment } from "./contact-table";

let permissions: string[] = [];
vi.mock("@/hooks/use-me", () => ({ usePermission: (code: string) => permissions.includes(code) }));

const CONTACT: RecallContact = {
  id: "c", customerId: "cust", customerCode: "TATA", customerName: "Tata Motors", status: "UNREACHABLE",
  contactPerson: "Asha Rao", contactDetails: "+91 98200 11000", assignedTo: null, assignedName: "Meera Iyer",
  followUpOn: "2026-11-02", notes: null, exceptionReason: null, attempts: 2, lastAttemptAt: null, acknowledgedOn: null,
  quantity: "100.000000", returnedQuantity: "30.000000", outstandingQuantity: "70.000000",
};

function show(contacts: RecallContact[], open = true) {
  vi.stubGlobal("fetch", vi.fn(async () => Response.json({ success: true, data: [] })));
  render(
    <QueryClientProvider client={new QueryClient()}>
      <ContactTable recallId="r" contacts={contacts} open={open} />
    </QueryClientProvider>,
  );
}

describe("ContactTable", () => {
  beforeEach(() => {
    permissions = ["RECALL_VIEW", "RECALL_MANAGE"];
  });

  it("shows a customer's progress and quantities", () => {
    show([CONTACT]);
    expect(screen.getByText(/Asha Rao, \+91 98200 11000/)).toBeInTheDocument();
    expect(screen.getByText(/Assigned to Meera Iyer/)).toBeInTheDocument();
    expect(screen.getByText(/100 dispatched, 30 returned, 70 still out/)).toBeInTheDocument();
  });

  it("lets a manager log attempts but not record exceptions", () => {
    show([CONTACT]);
    expect(screen.getByRole("button", { name: "Log attempt" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Record exception" })).not.toBeInTheDocument();
  });

  it("offers an exception to a closer for an unreachable customer", () => {
    permissions = ["RECALL_VIEW", "RECALL_CLOSE"];
    show([CONTACT]);
    expect(screen.getByRole("button", { name: "Record exception" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Log attempt" })).not.toBeInTheDocument();
  });

  it("is read-only once the recall stops being open", () => {
    show([CONTACT], false);
    expect(screen.queryByRole("button", { name: "Log attempt" })).not.toBeInTheDocument();
  });

  it("sends the status chosen, although the select returns to blank", async () => {
    show([CONTACT]);
    fireEvent.change(screen.getByLabelText("Move Tata Motors to"), { target: { value: "RESOLVED" } });
    await waitFor(() =>
      expect(fetch).toHaveBeenCalledWith(
        expect.stringContaining("/recalls/r/customers/cust/status"),
        expect.objectContaining({ body: JSON.stringify({ status: "RESOLVED" }) }),
      ),
    );
  });

  it("opens the attempt form", async () => {
    show([CONTACT]);
    fireEvent.click(screen.getByRole("button", { name: "Log attempt" }));
    expect(await screen.findByLabelText("Outcome")).toBeInTheDocument();
  });

  it("never puts an attempt in the future", () => {
    const now = new Date(2026, 9, 31, 8, 0);
    expect(attemptMoment("2026-10-31", now)).toBe(now.toISOString());
    expect(attemptMoment("2026-10-29", now)).toBe(new Date(2026, 9, 29, 12, 0).toISOString());
  });
});
