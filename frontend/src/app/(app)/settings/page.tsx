"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { NotificationPreferences } from "@/components/app/notification-preferences";
import { OrganizationForm } from "@/components/app/organization-form";
import { OrganizationLogoSettings } from "@/components/app/organization-logo";
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
    <form
      // A message from the last attempt goes, even when this one stops in the browser.
      onSubmit={(event) => {
        setFormError("");
        return handleSubmit(onSubmit)(event);
      }} className="space-y-4" noValidate>
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

/**
 * The person's own sign-in and emails, with the organisation's profile and logo. The organisation's rules are under
 * Settings. An operator of a platform organisation has no organisation profile to keep, so sees only their own.
 */
export default function AccountSettingsPage() {
  const { data: me } = useMe();
  const canManage = usePermission("TENANT_MANAGE");
  const customer = Boolean(me?.hasSubscription);
  const { data: organization } = useQuery({
    queryKey: ["organization", me?.tenantId],
    queryFn: () => api.get<Organization>(`/organizations/${me?.tenantId}`),
    enabled: customer,
  });
  return (
    <div className="max-w-2xl space-y-6 [&>section]:glass [&>section]:rounded-2xl [&>section]:p-5 sm:[&>section]:p-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight text-[#1b2a55] sm:text-3xl">
          My account
        </h1>
        <p className="mt-1 text-sm text-muted-foreground">
          {customer ? (
            <>
              Your sign-in and emails, and your organisation&apos;s profile. How
              the organisation works is under{" "}
              <Link className="underline" href="/settings/organisation">
                Settings
              </Link>
              .
            </>
          ) : me?.permissions.includes("PLATFORM_ADMIN") ? (
            <>
              Your sign-in. Settings for the whole platform are under{" "}
              <Link className="underline" href="/platform/settings">
                Platform settings
              </Link>{" "}
              in the Control Center.
            </>
          ) : (
            "Your sign-in and emails."
          )}
        </p>
      </div>
      {me && (
        <section className="space-y-2 text-sm">
          <h2 className="text-base font-semibold text-[#1b2a55]">You</h2>
          <p>{me.user.fullName}</p>
          <p className="text-muted-foreground">{me.user.email}</p>
        </section>
      )}
      {customer && (
        <section className="space-y-4">
          <h2 className="text-base font-semibold text-[#1b2a55]">
            Organisation profile
          </h2>
          {!canManage && (
            <p className="text-sm text-muted-foreground">
              Only an administrator can change the profile and logo.
            </p>
          )}
          {organization && (
            <OrganizationForm
              key={organization.version}
              organization={organization}
              submitLabel="Save changes"
              readOnly={!canManage}
            />
          )}
        </section>
      )}
      {customer && me && (
        <OrganizationLogoSettings
          tenantId={me.tenantId}
          canManage={canManage}
        />
      )}
      <section className="space-y-4">
        <h2 className="text-base font-semibold text-[#1b2a55]">
          Your password
        </h2>
        <ChangePasswordForm />
      </section>
      {customer && (
        <section>
          <NotificationPreferences />
        </section>
      )}
    </div>
  );
}
