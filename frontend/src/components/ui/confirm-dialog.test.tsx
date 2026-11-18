import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ConfirmProvider, useConfirm, type ConfirmOptions } from "./confirm-dialog";

function Asker({ options, onAnswer }: { options: ConfirmOptions; onAnswer: (answer: boolean) => void }) {
  const confirm = useConfirm();
  return <button onClick={() => confirm(options).then(onAnswer)}>Ask</button>;
}

function ask(options: ConfirmOptions) {
  const answers: boolean[] = [];
  render(
    <ConfirmProvider>
      <Asker options={options} onAnswer={(answer) => answers.push(answer)} />
    </ConfirmProvider>,
  );
  fireEvent.click(screen.getByRole("button", { name: "Ask" }));
  return answers;
}

const CANCEL_ORDER: ConfirmOptions = {
  title: "Cancel PO-1?",
  description: "This cannot be undone.",
  confirmLabel: "Cancel order",
  danger: true,
};

describe("confirmation", () => {
  it("asks, and answers yes only to the confirm button", async () => {
    const answers = ask(CANCEL_ORDER);
    const dialog = await screen.findByRole("alertdialog");
    expect(dialog).toHaveTextContent("Cancel PO-1?");
    expect(dialog).toHaveTextContent("This cannot be undone.");
    fireEvent.click(screen.getByRole("button", { name: "Cancel order" }));
    await waitFor(() => expect(answers).toEqual([true]));
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
  });

  it("answers no to Cancel and to Escape", async () => {
    const answers = ask(CANCEL_ORDER);
    fireEvent.click(await screen.findByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(answers).toEqual([false]));

    fireEvent.click(screen.getByRole("button", { name: "Ask" }));
    fireEvent.keyDown(await screen.findByRole("alertdialog"), { key: "Escape" });
    await waitFor(() => expect(answers).toEqual([false, false]));
  });

  it("starts with focus on Cancel, so a stray Enter does not confirm", async () => {
    ask(CANCEL_ORDER);
    const cancel = await screen.findByRole("button", { name: "Cancel" });
    await waitFor(() => expect(cancel).toHaveFocus());
  });
});
