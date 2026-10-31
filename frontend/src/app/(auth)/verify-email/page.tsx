"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { FormError } from "@/components/forms/field";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";

/** Confirms on a click rather than on load, because mail scanners open links and would spend the token. */
function VerifyEmail() {
  const token = useSearchParams().get("token") ?? "";
  const [state, setState] = useState<"idle" | "pending" | "done">("idle");
  const [error, setError] = useState("");

  async function confirm() {
    setState("pending");
    setError("");
    try {
      await api.post("/auth/email-verification/confirm", { token });
      setState("done");
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "Something went wrong.");
      setState("idle");
    }
  }

  if (state === "done") {
    return (
      <p className="text-sm">
        Your email address is confirmed.{" "}
        <Link href="/dashboard" className="underline">
          Continue
        </Link>
      </p>
    );
  }
  return (
    <div className="space-y-4">
      <h1 className="text-lg font-medium">Confirm your email address</h1>
      <FormError message={error} />
      <Button className="w-full" disabled={state === "pending"} onClick={confirm}>
        Confirm email
      </Button>
    </div>
  );
}

export default function VerifyEmailPage() {
  return (
    <Suspense>
      <VerifyEmail />
    </Suspense>
  );
}
