"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { applyServerErrors } from "@/components/forms/apply-server-errors";
import { Field, FormError } from "@/components/forms/field";
import { Button } from "@/components/ui/button";
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
      contactPhone: organization.contactPhone ?? "",
      timezone: organization.timezone,
      currency: organization.currency,
    },
  });

  const save = useMutation({
    mutationFn: (values: OrganizationValues) =>
      api.put<Organization>(`/organizations/${organization.id}`, {
        ...values,
        countryCode: organization.countryCode,
        locale: "en-IN",
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
      // A message from the last attempt goes, even when this one stops in the browser.
      onSubmit={(event) => {
        setFormError("");
        return handleSubmit((values) => save.mutate(values))(event);
      }}
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
        <Field
          label="Contact number"
          id="contactPhone"
          type="tel"
          placeholder="+91 20 1234 5678"
          error={errors.contactPhone}
          {...register("contactPhone")}
        />
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Time zone" id="timezone" error={errors.timezone} {...register("timezone")} />
          <Field label="Currency" id="currency" error={errors.currency} {...register("currency")} />
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
