"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useQuery } from "@tanstack/react-query";
import { useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { useForm } from "react-hook-form";
import { applyServerErrors } from "@/components/forms/apply-server-errors";
import { Field, FormError } from "@/components/forms/field";
import { Button } from "@/components/ui/button";
import { api, hardNavigate, session } from "@/lib/api";
import {
  acceptExistingAccountSchema,
  acceptNewAccountSchema,
  type AcceptInvitationValues,
} from "@/lib/schemas";
import type { InvitationPreview } from "@/lib/types";

function AcceptForm({ token, preview }: { token: string; preview: InvitationPreview }) {
  const { existingAccount } = preview;
  const [formError, setFormError] = useState("");
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<AcceptInvitationValues>({
    resolver: zodResolver(existingAccount ? acceptExistingAccountSchema : acceptNewAccountSchema),
    defaultValues: { fullName: "" },
  });

  async function onSubmit(values: AcceptInvitationValues) {
    setFormError("");
    try {
      await session.post("accept-invitation", {
        token,
        password: values.password,
        fullName: existingAccount ? undefined : values.fullName,
      });
      hardNavigate("/dashboard");
    } catch (error) {
      setFormError(applyServerErrors(error, setError));
    }
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="space-y-4" noValidate>
      <h1 className="text-lg font-medium">Join {preview.organizationName}</h1>
      <p className="text-sm text-muted-foreground">
        {existingAccount
          ? `Enter the password for ${preview.email} to accept.`
          : `You are joining as ${preview.email}.`}
      </p>
      <FormError message={formError} />
      {!existingAccount && <Field label="Your name" id="fullName" error={errors.fullName} {...register("fullName")} />}
      <Field
        label={existingAccount ? "Password" : "Choose a password"}
        id="password"
        type="password"
        autoComplete={existingAccount ? "current-password" : "new-password"}
        error={errors.password}
        {...register("password")}
      />
      <Button type="submit" className="w-full" disabled={isSubmitting}>
        Accept invitation
      </Button>
    </form>
  );
}

function AcceptInvitation() {
  const token = useSearchParams().get("token") ?? "";
  const { data, error } = useQuery({
    queryKey: ["invitation-preview", token],
    queryFn: () => api.post<InvitationPreview>("/auth/invitations/preview", { token }),
    retry: false,
  });
  if (error) return <FormError message={error.message} />;
  return data ? <AcceptForm token={token} preview={data} /> : null;
}

export default function AcceptInvitationPage() {
  return (
    <Suspense>
      <AcceptInvitation />
    </Suspense>
  );
}
