"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import Link from "next/link";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { applyServerErrors } from "@/components/forms/apply-server-errors";
import { Field, FormError } from "@/components/forms/field";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";
import { forgotPasswordSchema, type ForgotPasswordValues } from "@/lib/schemas";

export default function ForgotPasswordPage() {
  const [sent, setSent] = useState(false);
  const [formError, setFormError] = useState("");
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<ForgotPasswordValues>({ resolver: zodResolver(forgotPasswordSchema) });

  async function onSubmit(values: ForgotPasswordValues) {
    setFormError("");
    try {
      await api.post("/auth/password-reset/request", values);
      setSent(true);
    } catch (error) {
      setFormError(applyServerErrors(error, setError));
    }
  }

  if (sent) {
    return <p className="text-sm">If that address has an account, a reset link is on its way. It works for one hour.</p>;
  }
  return (
    <form onSubmit={handleSubmit(onSubmit)} className="space-y-4" noValidate>
      <h1 className="text-lg font-medium">Reset your password</h1>
      <FormError message={formError} />
      {/* The sign-in page stays mounted but hidden after navigating here, so "email" would label its input. */}
      <Field label="Email" id="reset-email" type="email" autoComplete="email" error={errors.email} {...register("email")} />
      <Button type="submit" className="w-full" disabled={isSubmitting}>
        Send reset link
      </Button>
      <p className="text-sm">
        <Link href="/login" className="underline">
          Back to sign in
        </Link>
      </p>
    </form>
  );
}
