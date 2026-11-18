"use client";

import { ReportExports } from "@/components/reports/report-exports";
import { PageHeader } from "@/components/records/parts";

export default function ReportExportsPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Your report exports"
        subtitle="Long reports exported to Excel. Each file is kept for 7 days."
      />
      <ReportExports />
    </div>
  );
}
