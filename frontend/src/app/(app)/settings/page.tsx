"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import Link from "next/link";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { NotificationPreferences } from "@/components/app/notification-preferences";
import { applyServerErrors } from "@/components/forms/apply-server-errors";
import { Field, FormError } from "@/components/forms/field";
import { Button } from "@/components/ui/button";
import { useMe } from "@/hooks/use-me";
import { api, hardNavigate } from "@/lib/api";
import { changePasswordSchema, type ChangePasswordValues } from "@/lib/schemas";

function ChangePasswordForm() {
  const [formError, setFormError] = useState("");
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<ChangePasswordValues>({
    resolver: zodResolver(changePasswordSchema),
  });

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
      <p className="text-sm text-muted-foreground">
        You will need to sign in again on all devices.
      </p>
      <Button type="submit" disabled={isSubmitting}>
        Change password
      </Button>
    </form>
  );
}

/** What belongs to the person, wherever they work: the organisation's own settings are under Organisation settings. */
export default function AccountSettingsPage() {
  const { data: me } = useMe();
  return (
    <div className="max-w-2xl space-y-6 [&>section]:glass [&>section]:rounded-2xl [&>section]:p-5 sm:[&>section]:p-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight text-[#1b2a55] sm:text-3xl">
          My account
        </h1>
        <p className="mt-1 text-sm text-muted-foreground">
          {me?.hasSubscription ? (
            <>
              Your own sign-in and emails. The organisation&apos;s profile and
              rules are under{" "}
              <Link className="underline" href="/settings/organisation">
                Organisation settings
              </Link>
              .
            </>
          ) : me?.permissions.includes("PLATFORM_ADMIN") ? (
            <>
              Your own sign-in and emails. Settings for the whole platform are
              under{" "}
              <Link className="underline" href="/platform/settings">
                Platform settings
              </Link>{" "}
              in the Control Center.
            </>
          ) : (
            "Your own sign-in and emails."
          )}
        </p>
      </div>
      {me && (
        <section className="space-y-2 text-sm">
          <h2 className="text-base font-semibold text-[#1b2a55]">Profile</h2>
          <p>{me.user.fullName}</p>
          <p className="text-muted-foreground">{me.user.email}</p>
        </section>
      )}
      <section className="space-y-4">
        <h2 className="text-base font-semibold text-[#1b2a55]">
          Your password
        </h2>
        <ChangePasswordForm />
      </section>
      {me?.hasSubscription && (
        <section>
          <NotificationPreferences />
        </section>
      )}
    </div>
  );
}
