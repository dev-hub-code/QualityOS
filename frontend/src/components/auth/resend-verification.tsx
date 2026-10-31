"use client";

import { useMutation } from "@tanstack/react-query";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { api } from "@/lib/api";

/** Says the same thing for every address, as the backend does. */
export function ResendVerification({ email }: { email?: string }) {
  const [typed, setTyped] = useState("");
  const address = email ?? typed;
  const resend = useMutation({
    mutationFn: () => api.post("/auth/resend-verification", { email: address }),
  });
  if (resend.isSuccess) {
    return (
      <p className="text-sm">If this address is waiting for confirmation, a new link is on its way.</p>
    );
  }
  return (
    <div className="space-y-2">
      {email === undefined && (
        <>
          <Label htmlFor="resend-email">Email</Label>
          <Input id="resend-email" type="email" value={typed} onChange={(event) => setTyped(event.target.value)} />
        </>
      )}
      <Button
        type="button"
        variant="outline"
        className="w-full"
        disabled={resend.isPending || !address}
        onClick={() => resend.mutate()}
      >
        Send a new link
      </Button>
    </div>
  );
}
