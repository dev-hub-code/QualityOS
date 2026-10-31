import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { AuditFinding } from "@/lib/types";
import { AuditFindings } from "./audit-findings";

const FINDING: AuditFinding = {
  id: "f", number: 1, grade: "OBSERVATION", statement: "Label faded", evidence: "VC-9", clauseReference: null,
  itemPosition: 2, ncr: null,
};

function show(findings: AuditFinding[], canRaise = true) {
  vi.stubGlobal("fetch", vi.fn(async () => Response.json({ success: true, data: {} })));
  render(
    <QueryClientProvider client={new QueryClient()}>
      <AuditFindings auditId="a" findings={findings} canRaise={canRaise} canAttach={false} />
    </QueryClientProvider>,
  );
}

describe("AuditFindings", () => {
  it("says when there are none", () => {
    show([]);
    expect(screen.getByText("No findings.")).toBeInTheDocument();
  });

  it("lets an observation raise an NCR", async () => {
    show([FINDING]);
    fireEvent.click(screen.getByRole("button", { name: "Raise NCR" }));
    await waitFor(() => expect(fetch).toHaveBeenCalledWith(expect.stringContaining("/audits/a/findings/f/ncr"), expect.anything()));
  });

  it("shows the NCR and CAPA of a finding that has one, instead of offering another", () => {
    show([
      {
        ...FINDING,
        ncr: {
          id: "n", ncrNumber: "NCR-1", status: "OPEN", finished: false,
          capas: [{ id: "c", capaNumber: "CAPA-1", title: "Fix", status: "OPEN" }],
        },
      },
    ]);
    expect(screen.getByRole("link", { name: "NCR-1" })).toHaveAttribute("href", "/ncrs/n");
    expect(screen.getByRole("link", { name: "CAPA-1" })).toHaveAttribute("href", "/capas/c");
    expect(screen.queryByRole("button", { name: "Raise NCR" })).not.toBeInTheDocument();
  });

  it("offers nothing to someone who may not raise one", () => {
    show([FINDING], false);
    expect(screen.queryByRole("button", { name: "Raise NCR" })).not.toBeInTheDocument();
  });
});
