"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import Link from "next/link";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { ResendVerification } from "@/components/auth/resend-verification";
import { applyServerErrors } from "@/components/forms/apply-server-errors";
import { Field, FormError } from "@/components/forms/field";
import { Button } from "@/components/ui/button";
import { ApiError, hardNavigate, session } from "@/lib/api";
import { loginSchema, type LoginValues } from "@/lib/schemas";

export default function LoginPage() {
  const [formError, setFormError] = useState("");
  const [unverifiedEmail, setUnverifiedEmail] = useState("");
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<LoginValues>({ resolver: zodResolver(loginSchema) });

  async function onSubmit(values: LoginValues) {
    setFormError("");
    try {
      await session.post("login", values);
      hardNavigate("/dashboard");
    } catch (error) {
      setFormError(applyServerErrors(error, setError));
      setUnverifiedEmail(error instanceof ApiError && error.code === "EMAIL_NOT_VERIFIED" ? values.email : "");
    }
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="space-y-4" noValidate>
      <h1 className="text-lg font-medium">Sign in</h1>
      <FormError message={formError} />
      {unverifiedEmail && <ResendVerification email={unverifiedEmail} />}
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
