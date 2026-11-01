"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import Link from "next/link";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { VerificationPending } from "@/components/auth/verification-pending";
import { applyServerErrors } from "@/components/forms/apply-server-errors";
import { Field, FormError } from "@/components/forms/field";
import { Button } from "@/components/ui/button";
import { hardNavigate, session } from "@/lib/api";
import { loginSchema, type LoginValues } from "@/lib/schemas";

export default function LoginPage() {
  const [formError, setFormError] = useState("");
  const [pendingEmail, setPendingEmail] = useState("");
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
        New to QualityOS?{" "}
        <Link href="/register" className="text-primary underline underline-offset-4">
          Create an organisation
        </Link>
      </p>
    </form>
  );
}
