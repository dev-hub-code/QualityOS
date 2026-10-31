"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import Link from "next/link";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { ResendVerification } from "@/components/auth/resend-verification";
import { applyServerErrors } from "@/components/forms/apply-server-errors";
import { Field, FormError } from "@/components/forms/field";
import { Button } from "@/components/ui/button";
import { hardNavigate, session } from "@/lib/api";
import { registerSchema, type RegisterValues } from "@/lib/schemas";

export default function RegisterPage() {
  const [formError, setFormError] = useState("");
  const [pendingEmail, setPendingEmail] = useState("");
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<RegisterValues>({ resolver: zodResolver(registerSchema) });

  async function onSubmit(values: RegisterValues) {
    setFormError("");
    try {
      const result = await session.post("register", values);
      if (result?.verificationRequired) {
        setPendingEmail(values.email);
      } else {
        hardNavigate("/onboarding");
      }
    } catch (error) {
      setFormError(applyServerErrors(error, setError));
    }
  }

  if (pendingEmail) {
    return (
      <div className="space-y-4">
        <h1 className="text-lg font-medium">Check your inbox</h1>
        <p className="text-sm text-muted-foreground">
          To finish, open the link we sent to {pendingEmail}. It works for a limited time.
        </p>
        <ResendVerification email={pendingEmail} />
      </div>
    );
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="space-y-4" noValidate>
      <h1 className="text-lg font-medium">Create your organisation</h1>
      <FormError message={formError} />
      <Field
        label="Organisation name"
        id="organizationName"
        autoComplete="organization"
        error={errors.organizationName}
        {...register("organizationName")}
      />
      <Field label="Your name" id="fullName" autoComplete="name" error={errors.fullName} {...register("fullName")} />
      <Field label="Work email" id="email" type="email" autoComplete="email" error={errors.email} {...register("email")} />
      <Field
        label="Password"
        id="password"
        type="password"
        autoComplete="new-password"
        error={errors.password}
        {...register("password")}
      />
      <Button type="submit" className="w-full" disabled={isSubmitting}>
        {isSubmitting ? "Creating…" : "Create organisation"}
      </Button>
      <p className="text-sm text-muted-foreground">
        Already registered?{" "}
        <Link href="/login" className="text-primary underline underline-offset-4">
          Sign in
        </Link>
      </p>
    </form>
  );
}
