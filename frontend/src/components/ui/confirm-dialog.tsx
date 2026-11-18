"use client";

import { CircleHelp, TriangleAlert } from "lucide-react";
import {
  createContext,
  useCallback,
  useContext,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogTitle,
} from "@/components/ui/dialog";

export interface ConfirmOptions {
  /** A question naming the action and the record, such as "Cancel PO-2026-00004?" */
  title: string;
  /** What will happen, and whether it can be undone. */
  description: ReactNode;
  /** The confirm button's label: the action itself, such as "Cancel order", never "OK". */
  confirmLabel: string;
  /** Cannot be undone, or removes something: a warning icon and a red button. */
  danger?: boolean;
}

type Confirm = (options: ConfirmOptions) => Promise<boolean>;

const ConfirmContext = createContext<Confirm | null>(null);

/**
 * Asks before an important action: {@code if (await confirm({...})) doIt()}. Focus starts on Cancel, so a stray Enter
 * or a double click never confirms, and closing the dialog any way counts as no.
 */
export function useConfirm(): Confirm {
  const confirm = useContext(ConfirmContext);
  if (!confirm) throw new Error("useConfirm needs a ConfirmProvider");
  return confirm;
}

export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [request, setRequest] = useState<
    (ConfirmOptions & { resolve: (answer: boolean) => void }) | null
  >(null);
  const cancel = useRef<HTMLButtonElement>(null);

  const confirm = useCallback<Confirm>(
    (options) =>
      new Promise<boolean>((resolve) => {
        setRequest({ ...options, resolve });
      }),
    [],
  );

  function answer(value: boolean) {
    request?.resolve(value);
    setRequest(null);
  }

  const Icon = request?.danger ? TriangleAlert : CircleHelp;

  return (
    <ConfirmContext.Provider value={confirm}>
      {children}
      <Dialog
        open={request !== null}
        onOpenChange={(open) => !open && answer(false)}
      >
        {request && (
          <DialogContent
            role="alertdialog"
            showCloseButton={false}
            initialFocus={cancel}
            className="sm:max-w-md"
          >
            <div className="flex gap-4">
              <span
                aria-hidden
                className={`grid size-10 shrink-0 place-items-center rounded-full ${
                  request.danger
                    ? "bg-red-100 text-red-600"
                    : "bg-[#2f4590]/10 text-[#2f4590]"
                }`}
              >
                <Icon className="size-5" />
              </span>
              <div className="min-w-0 space-y-1.5 pt-1">
                <DialogTitle>{request.title}</DialogTitle>
                <DialogDescription>{request.description}</DialogDescription>
              </div>
            </div>
            <DialogFooter>
              <Button
                ref={cancel}
                variant="outline"
                onClick={() => answer(false)}
              >
                Cancel
              </Button>
              <Button
                className={
                  request.danger
                    ? "bg-red-600 bg-none text-white shadow-[0_8px_20px_-10px_rgba(220,38,38,0.8)] hover:bg-red-700"
                    : undefined
                }
                onClick={() => answer(true)}
              >
                {request.confirmLabel}
              </Button>
            </DialogFooter>
          </DialogContent>
        )}
      </Dialog>
    </ConfirmContext.Provider>
  );
}
