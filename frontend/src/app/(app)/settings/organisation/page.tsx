"use client";

import { useQuery } from "@tanstack/react-query";
import { NcrStatusLabelSettings } from "@/components/app/ncr-status-labels";
import { OrganizationForm } from "@/components/app/organization-form";
import { OrganizationLogoSettings } from "@/components/app/organization-logo";
import { QualityRulesSettings } from "@/components/app/quality-rules";
import { ReviewPolicySettings } from "@/components/app/review-policy";
import { SupportAccessSettings } from "@/components/app/support-access";
import { useMe, usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import type { Organization } from "@/lib/types";

/** What the organisation is and how it works, for everyone in it. Only administrators change it. */
export default function OrganisationSettingsPage() {
  const { data: me } = useMe();
  const canManage = usePermission("TENANT_MANAGE");
  const { data: organization } = useQuery({
    queryKey: ["organization", me?.tenantId],
    queryFn: () => api.get<Organization>(`/organizations/${me?.tenantId}`),
    enabled: !!me,
  });

  return (
    <div className="max-w-2xl space-y-6 [&>section]:glass [&>section]:rounded-2xl [&>section]:p-5 sm:[&>section]:p-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight text-[#1b2a55] sm:text-3xl">
          Organisation settings
        </h1>
        <p className="mt-1 text-sm text-muted-foreground">
          {canManage
            ? "The organisation's profile and rules, for everyone in it."
            : "The organisation's profile and rules. Only an administrator can change them."}
        </p>
      </div>
      <section className="space-y-4">
        <h2 className="text-base font-semibold text-[#1b2a55]">Profile</h2>
        {organization && (
          <OrganizationForm
            key={organization.version}
            organization={organization}
            submitLabel="Save changes"
            readOnly={!canManage}
          />
        )}
      </section>
      {me && (
        <OrganizationLogoSettings
          tenantId={me.tenantId}
          canManage={canManage}
        />
      )}
      <ReviewPolicySettings />
      <QualityRulesSettings />
      <NcrStatusLabelSettings />
      <SupportAccessSettings />
    </div>
  );
}
