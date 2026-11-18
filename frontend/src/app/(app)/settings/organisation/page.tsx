"use client";

import { NcrStatusLabelSettings } from "@/components/app/ncr-status-labels";
import { QualityRulesSettings } from "@/components/app/quality-rules";
import { ReviewPolicySettings } from "@/components/app/review-policy";
import { SupportAccessSettings } from "@/components/app/support-access";
import { usePermission } from "@/hooks/use-me";

/** How the organisation works, for everyone in it. Only administrators change it. */
export default function OrganisationSettingsPage() {
  const canManage = usePermission("TENANT_MANAGE");
  return (
    <div className="max-w-2xl space-y-6 [&>section]:glass [&>section]:rounded-2xl [&>section]:p-5 sm:[&>section]:p-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight text-[#1b2a55] sm:text-3xl">
          Settings
        </h1>
        <p className="mt-1 text-sm text-muted-foreground">
          {canManage
            ? "How the organisation reviews work, handles NCRs and lets support in, for everyone in it."
            : "How the organisation reviews work, handles NCRs and lets support in. Only an administrator can change them."}
        </p>
      </div>
      <ReviewPolicySettings />
      <QualityRulesSettings />
      <NcrStatusLabelSettings />
      <SupportAccessSettings />
    </div>
  );
}
