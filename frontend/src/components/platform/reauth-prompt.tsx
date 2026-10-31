"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { api, ApiError, setReauthPrompt } from "@/lib/api";

/** Critical actions ask for the password again. The action then continues by itself. */
export function ReauthPrompt() {
  const [open, setOpen] = useState(false);
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const answer = useRef<((confirmed: boolean) => void) | null>(null);

  useEffect(() => {
    setReauthPrompt(
      () =>
        new Promise<boolean>((resolve) => {
          answer.current = resolve;
          setOpen(true);
        }),
    );
    return () => setReauthPrompt(null);
  }, []);

  function finish(confirmed: boolean) {
    setOpen(false);
    setPassword("");
    setError("");
    answer.current?.(confirmed);
    answer.current = null;
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    try {
      await api.post("/platform/reauthenticate", { password });
      finish(true);
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.message : "Something went wrong. Please try again.");
    }
  }

  return (
    <Dialog open={open} onOpenChange={(next) => !next && finish(false)}>
      <DialogContent className="z-[60]">
        <DialogHeader>
          <DialogTitle>Confirm your password</DialogTitle>
        </DialogHeader>
        <form className="space-y-4" onSubmit={submit}>
          <p className="text-sm text-muted-foreground">
            This is a critical action. You won&apos;t be asked again for 10 minutes.
          </p>
          <div className="space-y-1.5">
            <Label htmlFor="reauth-password">Password</Label>
            <Input
              id="reauth-password"
              type="password"
              autoComplete="current-password"
              autoFocus
              required
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </div>
          {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
          <div className="flex justify-end gap-2">
            <Button type="button" variant="outline" onClick={() => finish(false)}>Cancel</Button>
            <Button type="submit">Continue</Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
