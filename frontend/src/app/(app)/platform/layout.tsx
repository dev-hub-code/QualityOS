"use client";

import type { ReactNode } from "react";
import { ReauthPrompt } from "@/components/platform/reauth-prompt";
import { useMe } from "@/hooks/use-me";

/** A convenience only. The backend refuses every platform request on its own. */
export default function PlatformLayout({ children }: { children: ReactNode }) {
  const { data: me } = useMe();
  if (!me) return null;
  if (!me.permissions.includes("PLATFORM_ADMIN")) {
    return <p role="alert">You do not have access to the Control Center.</p>;
  }
  return (
    <>
      {children}
      <ReauthPrompt />
    </>
  );
}
