"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { formatMoney } from "@/components/billing/subscription-banner";
import { PageHeader, QueryState, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { api } from "@/lib/api";
import type { FieldDef } from "@/lib/record-form";
import type { Plan } from "@/lib/types";

const FEATURES = [
  ["NCR_CAPA", "NCR and CAPA"],
  ["REPORTS", "Dashboards and reports"],
  ["ADVANCED", "Traceability, recalls, calibration and audits"],
];

const FIELDS: FieldDef[] = [
  { name: "name", label: "Name", required: true },
  { name: "description", label: "Description", required: true },
  { name: "monthlyPrice", label: "Monthly price (blank for custom)", type: "decimal" },
  { name: "annualPrice", label: "Annual price (blank for custom)", type: "decimal" },
  { name: "taxPercent", label: "GST percent", type: "decimal", required: true },
  { name: "maxUsers", label: "User limit (blank for none)", type: "decimal" },
  { name: "maxFacilities", label: "Facility limit (blank for none)", type: "decimal" },
  { name: "trialDays", label: "Trial days", type: "decimal", required: true },
  { name: "graceDays", label: "Grace days", type: "decimal", required: true },
  ...FEATURES.map(([name, label]): FieldDef => ({ name, label, type: "checkbox" })),
  { name: "active", label: "Offered to customers", type: "checkbox" },
];

const NUMBERS = [
  "monthlyPrice",
  "annualPrice",
  "taxPercent",
  "maxUsers",
  "maxFacilities",
  "trialDays",
  "graceDays",
] as const;

const money = (value: number | null) => (value === null ? "Custom" : formatMoney(value));

export default function PlatformPlansPage() {
  const refresh = useRefresh();
  const [editing, setEditing] = useState<Plan | null>(null);
  const path = "/platform/plans";
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<Plan[]>(path) });
  if (!data) return <QueryState error={error} />;
  return (
    <div className="space-y-6">
      <PageHeader title="Plans" subtitle="Changes apply to new invoices. Issued invoices keep their figures." />
      <div className="overflow-x-auto">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Plan</TableHead>
              <TableHead className="text-right">Monthly</TableHead>
              <TableHead className="text-right">Annual</TableHead>
              <TableHead className="text-right">Users</TableHead>
              <TableHead className="text-right">Facilities</TableHead>
              <TableHead />
            </TableRow>
          </TableHeader>
          <TableBody>
            {data.map((plan) => (
              <TableRow key={plan.id}>
                <TableCell>
                  {plan.name}
                  {!plan.active && " (not offered)"}
                </TableCell>
                <TableCell className="text-right">{money(plan.monthlyPrice)}</TableCell>
                <TableCell className="text-right">{money(plan.annualPrice)}</TableCell>
                <TableCell className="text-right">{plan.maxUsers ?? "No limit"}</TableCell>
                <TableCell className="text-right">{plan.maxFacilities ?? "No limit"}</TableCell>
                <TableCell>
                  <Button size="sm" variant="outline" onClick={() => setEditing(plan)}>
                    Edit {plan.name}
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      {editing && (
        <RecordDialog
          title={`Edit ${editing.name}`}
          fields={FIELDS}
          record={{
            ...editing,
            ...Object.fromEntries(
              NUMBERS.map((name) => [name, editing[name] === null ? "" : String(editing[name])]),
            ),
            ...Object.fromEntries(FEATURES.map(([code]) => [code, editing.features.includes(code)])),
          }}
          onClose={() => setEditing(null)}
          onSubmit={async (values) => {
            const features = FEATURES.map(([code]) => code).filter((code) => values[code]);
            await api.put(`${path}/${editing.id}`, { ...values, features, version: editing.version });
            setEditing(null);
            await refresh();
          }}
        />
      )}
    </div>
  );
}
