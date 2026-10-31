import { formatQuantity } from "@/lib/record-form";
import type { RecallDashboard } from "@/lib/types";

export function RecallDashboardTiles({ dashboard }: { dashboard: RecallDashboard }) {
  const tiles: [string, string | number][] = [
    ["Affected customers", dashboard.totalCustomers],
    ["Pending contact", dashboard.pending],
    ["Contact attempts", dashboard.contactAttempts],
    ["Contacted", dashboard.contacted],
    ["Acknowledged", dashboard.acknowledged],
    ["Unreachable", dashboard.unreachable],
    ["Returns in progress", dashboard.returnsInProgress],
    ["Resolved", dashboard.resolved],
    ["Follow-ups due", dashboard.outstandingFollowUps],
    ["Still with customers", formatQuantity(dashboard.outstandingQuantity)],
  ];
  return (
    <dl className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
      {tiles.map(([label, value]) => (
        <div key={label} className="rounded-lg border p-3">
          <dt className="text-xs text-muted-foreground">{label}</dt>
          <dd className="text-xl font-semibold">{value}</dd>
        </div>
      ))}
    </dl>
  );
}
