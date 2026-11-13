"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import Link from "next/link";
import { useState, type FormEvent } from "react";
import { useForm } from "react-hook-form";
import { VerificationPending } from "@/components/auth/verification-pending";
import { applyServerErrors } from "@/components/forms/apply-server-errors";
import { Field, FormError } from "@/components/forms/field";
import { Button } from "@/components/ui/button";
import { ApiError, hardNavigate, session } from "@/lib/api";
import { loginSchema, type LoginValues } from "@/lib/schemas";

/** The second step of an operator's sign-in. The challenge from the first step is held in a cookie. */
function SecondFactorStep({ onBack }: { onBack: () => void }) {
  const [code, setCode] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError("");
    setBusy(true);
    try {
      await session.post("login/second-factor", { code });
      hardNavigate("/start");
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.message : "Something went wrong. Please try again.");
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="space-y-4" noValidate>
      <h1 className="text-lg font-medium">Enter your authenticator code</h1>
      <p className="text-sm text-muted-foreground">
        Open your authenticator app and enter the six-digit code it shows for ProductionOS.
      </p>
      <FormError message={error} />
      <Field
        label="Six-digit code"
        id="code"
        inputMode="numeric"
        autoComplete="one-time-code"
        pattern="[0-9]{6}"
        maxLength={6}
        autoFocus
        required
        value={code}
        onChange={(event) => setCode(event.target.value.replace(/\D/g, ""))}
      />
      <Button type="submit" className="w-full" disabled={busy || code.length !== 6}>
        {busy ? "Signing in…" : "Continue"}
      </Button>
      <p className="text-sm">
        <button type="button" className="underline" onClick={onBack}>
          Back to sign in
        </button>
      </p>
    </form>
  );
}

export default function LoginPage() {
  const [formError, setFormError] = useState("");
  const [pendingEmail, setPendingEmail] = useState("");
  const [secondFactor, setSecondFactor] = useState(false);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<LoginValues>({ resolver: zodResolver(loginSchema) });

  async function onSubmit(values: LoginValues) {
    setFormError("");
    try {
      const result = await session.post("login", values);
      if (result?.verificationRequired) {
        setPendingEmail(values.email);
      } else if (result?.secondFactorRequired) {
        setSecondFactor(true);
      } else {
        hardNavigate("/start");
      }
    } catch (error) {
      setFormError(applyServerErrors(error, setError));
    }
  }

  if (pendingEmail) {
    return <VerificationPending email={pendingEmail} />;
  }
  if (secondFactor) {
    return <SecondFactorStep onBack={() => setSecondFactor(false)} />;
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="space-y-4" noValidate>
      <h1 className="text-lg font-medium">Sign in</h1>
      <FormError message={formError} />
      <Field label="Email" id="email" type="email" autoComplete="email" error={errors.email} {...register("email")} />
      <Field
        label="Password"
        id="password"
        type="password"
        autoComplete="current-password"
        error={errors.password}
        {...register("password")}
      />
      <Button type="submit" className="w-full" disabled={isSubmitting}>
        {isSubmitting ? "Signing in…" : "Sign in"}
      </Button>
      <p className="text-sm">
        <Link href="/forgot-password" className="underline">
          Forgot your password?
        </Link>
      </p>
      <p className="text-sm text-muted-foreground">
        New to ProductionOS?{" "}
        <Link href="/register" className="text-primary underline underline-offset-4">
          Create an organisation
        </Link>
      </p>
    </form>
  );
}
