"use client";

import { useQuery } from "@tanstack/react-query";
import { OrganizationForm } from "@/components/app/organization-form";
import { useMe } from "@/hooks/use-me";
import { api, hardNavigate } from "@/lib/api";
import type { Organization } from "@/lib/types";

export default function OnboardingPage() {
  const { data: me } = useMe();
  const { data: organization } = useQuery({
    queryKey: ["organization", me?.tenantId],
    queryFn: () => api.get<Organization>(`/organizations/${me?.tenantId}`),
    enabled: !!me,
  });

  return (
    <main className="mx-auto w-full max-w-lg space-y-6 px-4 py-12">
      <header className="space-y-1">
        <p className="font-heading text-2xl font-semibold tracking-tight text-primary">QualityOS</p>
        <h1 className="text-lg font-medium">Tell us about your organisation</h1>
        <p className="text-sm text-muted-foreground">
          These details appear on inspection reports. You can change them later.
        </p>
      </header>
      {organization && (
        <OrganizationForm
          organization={organization}
          submitLabel="Continue"
          onSaved={() => hardNavigate("/dashboard")}
        />
      )}
    </main>
  );
}
