import { describe, expect, it } from "vitest";
import { formatChecklist, parseChecklist } from "./checklist-text";

describe("checklist text", () => {
  it("reads one question a line, with an optional clause, and skips blanks", () => {
    expect(parseChecklist("Are gauges labelled? | 7.1.5\n\n  Are records kept?  \n")).toEqual([
      { question: "Are gauges labelled?", clauseReference: "7.1.5" },
      { question: "Are records kept?", clauseReference: null },
    ]);
  });

  it("writes back what it read", () => {
    const text = "Are gauges labelled? | 7.1.5\nAre records kept?";
    expect(formatChecklist(parseChecklist(text))).toBe(text);
  });
});
