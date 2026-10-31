"use client";

import { useQuery } from "@tanstack/react-query";
import { ResendVerification } from "@/components/auth/resend-verification";
import { Button } from "@/components/ui/button";
import { api, hardNavigate } from "@/lib/api";

const CHECK_EVERY_MS = 3_000;

interface VerificationStatus {
  emailVerified: boolean;
}

/** Works from the restricted session that the right password earns on an unconfirmed account. */
export function VerificationPending({ email }: { email: string }) {
  const { data } = useQuery({
    queryKey: ["/auth/verification-status"],
    queryFn: () => api.get<VerificationStatus>("/auth/verification-status"),
    refetchInterval: (query) => (query.state.data?.emailVerified ? false : CHECK_EVERY_MS),
  });
  if (data?.emailVerified) {
    return (
      <div className="space-y-4">
        <p className="text-sm">Your email is confirmed. You can sign in now.</p>
        <Button className="w-full" onClick={() => hardNavigate("/login")}>
          Continue
        </Button>
      </div>
    );
  }
  return (
    <div className="space-y-4">
      <h1 className="text-lg font-medium">Confirm your email address</h1>
      <p className="text-sm text-muted-foreground">
        Open the link we sent to {email}. This page updates when it is done.
      </p>
      <ResendVerification email={email} />
    </div>
  );
}
