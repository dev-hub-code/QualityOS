"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { EmailLanguage } from "@/components/app/email-language";
import { NotificationPreferences } from "@/components/app/notification-preferences";
import { OrganizationForm } from "@/components/app/organization-form";
import { applyServerErrors } from "@/components/forms/apply-server-errors";
import { Field, FormError } from "@/components/forms/field";
import { Button } from "@/components/ui/button";
import { useMe, usePermission } from "@/hooks/use-me";
import { api, hardNavigate } from "@/lib/api";
import { changePasswordSchema, type ChangePasswordValues } from "@/lib/schemas";
import type { Organization } from "@/lib/types";

function ChangePasswordForm() {
  const [formError, setFormError] = useState("");
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<ChangePasswordValues>({ resolver: zodResolver(changePasswordSchema) });

  async function onSubmit(values: ChangePasswordValues) {
    setFormError("");
    try {
      await api.post("/auth/change-password", values);
      hardNavigate("/login");
    } catch (error) {
      setFormError(applyServerErrors(error, setError));
    }
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="space-y-4" noValidate>
      <FormError message={formError} />
      <Field
        label="Current password"
        id="currentPassword"
        type="password"
        autoComplete="current-password"
        error={errors.currentPassword}
        {...register("currentPassword")}
      />
      <Field
        label="New password"
        id="newPassword"
        type="password"
        autoComplete="new-password"
        error={errors.newPassword}
        {...register("newPassword")}
      />
      <p className="text-sm text-muted-foreground">You will need to sign in again on all devices.</p>
      <Button type="submit" disabled={isSubmitting}>
        Change password
      </Button>
    </form>
  );
}

export default function SettingsPage() {
  const { data: me } = useMe();
  const canManage = usePermission("TENANT_MANAGE");
  const { data: organization } = useQuery({
    queryKey: ["organization", me?.tenantId],
    queryFn: () => api.get<Organization>(`/organizations/${me?.tenantId}`),
    enabled: !!me,
  });

  return (
    <div className="max-w-lg space-y-10">
      <h1 className="text-2xl font-semibold tracking-tight">Settings</h1>
      <section className="space-y-4">
        <h2 className="font-medium">Organisation</h2>
        {organization && (
          <OrganizationForm
            key={organization.version}
            organization={organization}
            submitLabel="Save changes"
            readOnly={!canManage}
          />
        )}
      </section>
      <section className="space-y-4">
        <h2 className="font-medium">Your password</h2>
        <ChangePasswordForm />
      </section>
      <EmailLanguage />
      <NotificationPreferences />
    </div>
  );
}
