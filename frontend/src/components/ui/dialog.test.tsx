import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Dialog, DialogContent } from "./dialog";

function Open() {
  return (
    <Dialog open>
      <DialogContent showCloseButton={false}>
        <input aria-label="First" />
        <button>Save</button>
        <button>Close</button>
      </DialogContent>
    </Dialog>
  );
}

describe("dialog focus", () => {
  it("wraps Tab from the last control to the first, and Shift+Tab from the first to the last", () => {
    render(<Open />);
    const first = screen.getByLabelText("First");
    const last = screen.getByRole("button", { name: "Close" });

    last.focus();
    fireEvent.keyDown(last, { key: "Tab" });
    expect(document.activeElement).toBe(first);

    fireEvent.keyDown(first, { key: "Tab", shiftKey: true });
    expect(document.activeElement).toBe(last);
  });

  it("leaves Tab alone between controls in the middle", () => {
    render(<Open />);
    const save = screen.getByRole("button", { name: "Save" });
    save.focus();
    fireEvent.keyDown(save, { key: "Tab" });
    expect(document.activeElement).toBe(save);
  });
});
