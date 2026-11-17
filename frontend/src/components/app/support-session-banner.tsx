"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { hardNavigate } from "@/lib/api";

/** Always on screen in a support session, so the operator never forgets whose records they are reading. */
export function SupportSessionBanner({ organizationName, expiresAt }: { organizationName: string; expiresAt: string }) {
  const [leaving, setLeaving] = useState(false);
  async function leave() {
    setLeaving(true);
    const response = await fetch("/api/auth/end-support-session", { method: "POST", headers: { "content-type": "application/json" }, body: "{}" });
    const signedOut = (await response.json().catch(() => null))?.data?.signedOut;
    hardNavigate(signedOut ? "/login" : "/platform/dashboard");
  }
  return (
    <div
      role="status"
      className="mb-4 flex flex-wrap items-center justify-between gap-2 rounded-2xl border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900 print:hidden"
    >
      <span>
        Support view of <strong>{organizationName}</strong>: read-only, until{" "}
        {new Date(expiresAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}. Every page you open is
        shown to the organisation.
      </span>
      <Button variant="outline" size="sm" disabled={leaving} onClick={leave}>
        End support session
      </Button>
    </div>
  );
}
