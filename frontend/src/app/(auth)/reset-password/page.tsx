"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { useForm } from "react-hook-form";
import { applyServerErrors } from "@/components/forms/apply-server-errors";
import { Field, FormError } from "@/components/forms/field";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";
import { resetPasswordSchema, type ResetPasswordValues } from "@/lib/schemas";

function ResetPasswordForm() {
  const token = useSearchParams().get("token") ?? "";
  const [done, setDone] = useState(false);
  const [formError, setFormError] = useState("");
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<ResetPasswordValues>({ resolver: zodResolver(resetPasswordSchema) });

  async function onSubmit(values: ResetPasswordValues) {
    setFormError("");
    try {
      await api.post("/auth/password-reset/confirm", { token, newPassword: values.newPassword });
      setDone(true);
    } catch (error) {
      setFormError(applyServerErrors(error, setError));
    }
  }

  if (done) {
    return (
      <p className="text-sm">
        Your password has changed.{" "}
        <Link href="/login" className="underline">
          Sign in
        </Link>
      </p>
    );
  }
  return (
    <form onSubmit={handleSubmit(onSubmit)} className="space-y-4" noValidate>
      <h1 className="text-lg font-medium">Choose a new password</h1>
      <FormError message={formError} />
      <Field
        label="New password"
        id="newPassword"
        type="password"
        autoComplete="new-password"
        error={errors.newPassword}
        {...register("newPassword")}
      />
      <Button type="submit" className="w-full" disabled={isSubmitting}>
        Change password
      </Button>
    </form>
  );
}

export default function ResetPasswordPage() {
  return (
    <Suspense>
      <ResetPasswordForm />
    </Suspense>
  );
}
