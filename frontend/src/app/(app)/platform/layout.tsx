"use client";

import { useQuery } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { ReauthPrompt } from "@/components/platform/reauth-prompt";
import { SecondFactorSetup } from "@/components/platform/second-factor-setup";
import { useMe } from "@/hooks/use-me";
import { api } from "@/lib/api";
import type { SecondFactorState } from "@/lib/types";

/**
 * A convenience only. The backend refuses every platform request on its own, including any from an operator who
 * has not set up their authenticator app, which is why that page comes before everything else here.
 */
export default function PlatformLayout({ children }: { children: ReactNode }) {
  const { data: me } = useMe();
  const operator = me?.permissions.includes("PLATFORM_ADMIN") ?? false;
  const { data: factor, refetch } = useQuery({
    queryKey: ["/platform/second-factor"],
    queryFn: () => api.get<SecondFactorState>("/platform/second-factor"),
    enabled: operator,
  });
  if (!me) return null;
  if (!operator) {
    return <p role="alert">You do not have access to the Control Center.</p>;
  }
  if (!factor) return null;
  if (factor.state !== "ENROLLED") {
    return <SecondFactorSetup onDone={() => refetch()} />;
  }
  return (
    <>
      {children}
      <ReauthPrompt />
    </>
  );
}
