"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { EMAIL_LANGUAGES } from "@/components/app/email-language";
import { applyServerErrors } from "@/components/forms/apply-server-errors";
import { Field, FormError } from "@/components/forms/field";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { api } from "@/lib/api";
import { organizationSchema, type OrganizationValues } from "@/lib/schemas";
import type { Organization } from "@/lib/types";

interface OrganizationFormProps {
  organization: Organization;
  submitLabel: string;
  readOnly?: boolean;
  onSaved?: () => void;
}

export function OrganizationForm({ organization, submitLabel, readOnly, onSaved }: OrganizationFormProps) {
  const queryClient = useQueryClient();
  const [formError, setFormError] = useState("");
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<OrganizationValues>({
    resolver: zodResolver(organizationSchema),
    defaultValues: {
      name: organization.name,
      legalName: organization.legalName ?? "",
      gstin: organization.gstin ?? "",
      industry: organization.industry ?? "",
      address: organization.address ?? "",
      timezone: organization.timezone,
      currency: organization.currency,
    },
  });

  const [language, setLanguage] = useState(new Intl.Locale(organization.locale).language);

  const save = useMutation({
    mutationFn: (values: OrganizationValues) =>
      api.put<Organization>(`/organizations/${organization.id}`, {
        ...values,
        countryCode: organization.countryCode,
        locale: `${language}-IN`,
        version: organization.version,
      }),
    onSuccess: async () => {
      await queryClient.invalidateQueries();
      onSaved?.();
    },
    onError: (error) => setFormError(applyServerErrors(error, setError)),
  });

  return (
    <form
      onSubmit={handleSubmit((values) => {
        setFormError("");
        save.mutate(values);
      })}
      className="space-y-4"
      noValidate
    >
      <FormError message={formError} />
      <fieldset disabled={readOnly} className="space-y-4">
        <Field label="Organisation name" id="name" error={errors.name} {...register("name")} />
        <Field label="Legal name" id="legalName" error={errors.legalName} {...register("legalName")} />
        <Field label="GSTIN" id="gstin" error={errors.gstin} {...register("gstin")} />
        <Field label="Industry" id="industry" placeholder="CNC machining" error={errors.industry} {...register("industry")} />
        <Field label="Address" id="address" error={errors.address} {...register("address")} />
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Time zone" id="timezone" error={errors.timezone} {...register("timezone")} />
          <Field label="Currency" id="currency" error={errors.currency} {...register("currency")} />
        </div>
        <div className="space-y-2">
          <Label htmlFor="organization-language">Default email language</Label>
          <select
            id="organization-language"
            className="h-9 w-full rounded-md border bg-background px-3 text-sm"
            value={language}
            onChange={(event) => setLanguage(event.target.value)}
          >
            {EMAIL_LANGUAGES.map(({ value, label }) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </div>
      </fieldset>
      {!readOnly && (
        <Button type="submit" disabled={save.isPending}>
          {save.isPending ? "Saving…" : submitLabel}
        </Button>
      )}
    </form>
  );
}
