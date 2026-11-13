"use client";

import { QualityDashboard, Welcome } from "@/components/analytics/quality-dashboard";
import { DashboardFrame } from "@/components/app/showcase";
import { useMe, usePermission } from "@/hooks/use-me";

export default function DashboardPage() {
  const { data: me } = useMe();
  const canSeeQuality = usePermission("REPORT_VIEW");
  const name = me?.user.fullName;

  if (canSeeQuality) return <QualityDashboard name={name} />;
  return (
    <DashboardFrame>
      <Welcome name={name}>
        <p className="max-w-lg text-base text-slate-600 sm:text-lg">Use the menu to reach the work assigned to you.</p>
      </Welcome>
    </DashboardFrame>
  );
}
