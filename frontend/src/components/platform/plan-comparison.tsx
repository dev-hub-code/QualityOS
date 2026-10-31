import { formatMoney } from "@/components/billing/subscription-banner";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { Plan } from "@/lib/types";

export const PLAN_FEATURES = [
  ["NCR_CAPA", "NCR and CAPA"],
  ["REPORTS", "Dashboards and reports"],
  ["ADVANCED", "Traceability, recalls, calibration and audits"],
];

const money = (value: number | null) => (value === null ? "Custom" : formatMoney(value));
const limit = (value: number | null) => value ?? "No limit";

export function PlanComparison({ plans }: { plans: Plan[] }) {
  const rows: [string, (plan: Plan) => string | number][] = [
    ["Monthly", (plan) => money(plan.monthlyPrice)],
    ["Annual", (plan) => money(plan.annualPrice)],
    ["Users", (plan) => limit(plan.maxUsers)],
    ["Facilities", (plan) => limit(plan.maxFacilities)],
    ["Trial days", (plan) => plan.trialDays],
    ["Grace days", (plan) => plan.graceDays],
    ...PLAN_FEATURES.map(([code, label]): [string, (plan: Plan) => string] => [
      label,
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
