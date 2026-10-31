"use client";

import { QualityDashboard } from "@/components/analytics/quality-dashboard";
import { useMe, usePermission } from "@/hooks/use-me";

export default function DashboardPage() {
  const { data: me } = useMe();
  const canSeeQuality = usePermission("REPORT_VIEW");

  return (
    <div className="space-y-6">
      <div className="space-y-2">
        <h1 className="text-2xl font-semibold tracking-tight">Welcome, {me?.user.fullName}</h1>
        {!canSeeQuality && (
          <p className="text-muted-foreground">Use the menu to reach the work assigned to you.</p>
        )}
      </div>
      {canSeeQuality && <QualityDashboard />}
    </div>
  );
}
