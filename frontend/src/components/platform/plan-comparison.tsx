import { formatMoney } from "@/components/billing/subscription-banner";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { Feature, Plan } from "@/lib/types";

const money = (value: number | null) => (value === null ? "Custom" : formatMoney(value));
const limit = (value: number | null) => value ?? "No limit";

export function PlanComparison({ plans, features }: { plans: Plan[]; features: Feature[] }) {
  const rows: [string, (plan: Plan) => string | number][] = [
    ["Monthly", (plan) => money(plan.monthlyPrice)],
    ["Annual", (plan) => money(plan.annualPrice)],
    ["Users", (plan) => limit(plan.maxUsers)],
    ["Facilities", (plan) => limit(plan.maxFacilities)],
    ["Storage (MB)", (plan) => limit(plan.maxStorageMb)],
    ["Inspections a month", (plan) => limit(plan.maxMonthlyInspections)],
    ["Attachments", (plan) => limit(plan.maxAttachments)],
    ["Trial days", (plan) => plan.trialDays],
    ["Grace days", (plan) => plan.graceDays],
    ...features.map(({ code, name }): [string, (plan: Plan) => string] => [
      name,
      (plan) => (plan.features.includes(code) ? "Included" : "Not included"),
    ]),
  ];
  return (
    <div className="overflow-x-auto">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead />
            {plans.map((plan) => (
              <TableHead key={plan.id}>{plan.name}</TableHead>
            ))}
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map(([label, value]) => (
            <TableRow key={label}>
              <TableCell className="font-medium">{label}</TableCell>
              {plans.map((plan) => (
                <TableCell key={plan.id}>{value(plan)}</TableCell>
              ))}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}
