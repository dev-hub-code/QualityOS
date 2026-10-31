"use client";

import { useMutation } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";

export function VerifyEmailBanner({ email }: { email: string }) {
  const resend = useMutation({ mutationFn: () => api.post("/auth/resend-verification", { email }) });
  return (
    <div className="mb-4 flex flex-wrap items-center justify-between gap-2 rounded-lg border bg-muted/40 p-3 text-sm print:hidden">
      <span>
        {resend.isSuccess ? "Check your inbox for a new link." : "Please confirm your email address."}
      </span>
      {!resend.isSuccess && (
        <Button variant="outline" size="sm" disabled={resend.isPending} onClick={() => resend.mutate()}>
          Resend link
        </Button>
      )}
    </div>
  );
}
