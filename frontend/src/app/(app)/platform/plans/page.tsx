"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useState } from "react";
import { formatMoney } from "@/components/billing/subscription-banner";
import { PageHeader, QueryState, Section, useRefresh } from "@/components/records/parts";
import { PlanComparison } from "@/components/platform/plan-comparison";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { api } from "@/lib/api";
import type { FieldDef } from "@/lib/record-form";
import type { Feature, Plan } from "@/lib/types";


const FIELDS: FieldDef[] = [
  { name: "name", label: "Name", required: true },
  { name: "description", label: "Description", required: true },
  { name: "monthlyPrice", label: "Monthly price (blank for custom)", type: "decimal" },
  { name: "annualPrice", label: "Annual price (blank for custom)", type: "decimal" },
  { name: "taxPercent", label: "GST percent", type: "decimal", required: true },
  { name: "maxUsers", label: "User limit (blank for none)", type: "decimal" },
  { name: "maxFacilities", label: "Facility limit (blank for none)", type: "decimal" },
  { name: "maxStorageMb", label: "Storage limit in MB (blank for none)", type: "decimal" },
  { name: "maxMonthlyInspections", label: "Inspections a month (blank for none)", type: "decimal" },
  { name: "maxAttachments", label: "Attachment limit (blank for none)", type: "decimal" },
  { name: "trialDays", label: "Trial days", type: "decimal", required: true },
  { name: "graceDays", label: "Grace days", type: "decimal", required: true },
  { name: "active", label: "Offered to customers", type: "checkbox" },
  { name: "publicVisible", label: "Shown on the website", type: "checkbox" },
  { name: "displayOrder", label: "Display order", type: "decimal", required: true },
];

const NEW_PLAN: FieldDef[] = [
  { name: "code", label: "Code, such as GROWTH_PLUS", required: true },
  { name: "name", label: "Name", required: true },
];
const REASON: FieldDef[] = [{ name: "reason", label: "Reason", type: "textarea", required: true }];

const NUMBERS = [
  "monthlyPrice",
  "annualPrice",
  "taxPercent",
  "maxUsers",
  "maxFacilities",
  "maxStorageMb",
  "maxMonthlyInspections",
  "maxAttachments",
  "trialDays",
  "graceDays",
  "displayOrder",
] as const;

const money = (value: number | null) => (value === null ? "Custom" : formatMoney(value));

export default function PlatformPlansPage() {
  const refresh = useRefresh();
  const [editing, setEditing] = useState<Plan | null>(null);
  const [adding, setAdding] = useState(false);
  const [copying, setCopying] = useState<Plan | null>(null);
  const [acting, setActing] = useState<{ plan: Plan; action: "activate" | "deactivate" | "archive" } | null>(null);
  const path = "/platform/plans";
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<Plan[]>(path) });
  const impact = useQuery({
    queryKey: [path, editing?.id, "impact"],
    queryFn: () => api.get<{ subscribers: number }>(`${path}/${editing?.id}/impact`),
    enabled: editing !== null,
  });
  const subscribers = impact.data?.subscribers ?? 0;
  const features = useQuery({
    queryKey: ["/platform/features"],
    queryFn: () => api.get<Feature[]>("/platform/features"),
  });
  const catalogue = features.data ?? [];
  if (!data) return <QueryState error={error} />;
  const listed = [...data].sort((a, b) => Number(a.archivedAt !== null) - Number(b.archivedAt !== null));
  return (
    <div className="space-y-6">
      <PageHeader title="Plans" subtitle="Changes apply to new invoices. Issued invoices keep their figures.">
        <Button onClick={() => setAdding(true)}>New plan</Button>
      </PageHeader>
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
            {listed.map((plan) => (
              <TableRow key={plan.id}>
                <TableCell>
                  <Link className="underline" href={`/platform/plans/${plan.id}`}>
                    {plan.name}
                  </Link>
                  {plan.archivedAt ? " (archived)" : !plan.active && " (not offered)"}
                </TableCell>
                <TableCell className="text-right">{money(plan.monthlyPrice)}</TableCell>
                <TableCell className="text-right">{money(plan.annualPrice)}</TableCell>
                <TableCell className="text-right">{plan.maxUsers ?? "No limit"}</TableCell>
                <TableCell className="text-right">{plan.maxFacilities ?? "No limit"}</TableCell>
                <TableCell className="space-x-2 whitespace-nowrap">
                  {!plan.archivedAt && (
                    <>
                      <Button size="sm" variant="outline" onClick={() => setEditing(plan)}>
                        Edit {plan.name}
                      </Button>
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => setActing({ plan, action: plan.active ? "deactivate" : "activate" })}
                      >
                        {plan.active ? "Deactivate" : "Activate"} {plan.name}
                      </Button>
                      <Button size="sm" variant="outline" onClick={() => setActing({ plan, action: "archive" })}>
                        Archive {plan.name}
                      </Button>
                    </>
                  )}
                  <Button size="sm" variant="outline" onClick={() => setCopying(plan)}>
                    Copy {plan.name}
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      <Section title="Compare plans">
        <PlanComparison plans={data.filter((plan) => !plan.archivedAt)} features={catalogue} />
      </Section>
      {adding && (
        <RecordDialog
          title="New plan"
          fields={NEW_PLAN}
          onClose={() => setAdding(false)}
          onSubmit={async (values) => {
            await api.post(path, values);
            await refresh();
          }}
        />
      )}
      {copying && (
        <RecordDialog
          title={`Copy ${copying.name}`}
          fields={NEW_PLAN}
          onClose={() => setCopying(null)}
          onSubmit={async (values) => {
            await api.post(`${path}/${copying.id}/duplicate`, values);
            await refresh();
          }}
        />
      )}
      {acting && (
        <RecordDialog
          title={`${acting.action[0].toUpperCase()}${acting.action.slice(1)} ${acting.plan.name}`}
          fields={REASON}
          onClose={() => setActing(null)}
          onSubmit={async ({ reason }) => {
            const { plan, action } = acting;
            const body = { version: plan.version, reason };
            await (action === "archive"
              ? api.post(`${path}/${plan.id}/archive`, body)
              : api.post(`${path}/${plan.id}/active`, { ...body, active: action === "activate" }));
            await refresh();
          }}
        />
      )}
      {editing && impact.data && (
        <RecordDialog
          title={`Edit ${editing.name}`}
          fields={[
            ...FIELDS,
            ...catalogue
              .filter(({ code, active }) => active || editing.features.includes(code))
              .map(({ code, name }): FieldDef => ({ name: code, label: name, type: "checkbox" })),
            { name: "reason", label: "Reason", type: "textarea", required: subscribers > 0 },
          ]}
          extra={
            subscribers > 0 && (
              <p role="status" className="text-sm">
                {subscribers} {subscribers === 1 ? "organisation is" : "organisations are"} on this plan. Limits and
                features change for them at once. A new price applies from their next invoice.
              </p>
            )
          }
          record={{
            ...editing,
            ...Object.fromEntries(
              NUMBERS.map((name) => [name, editing[name] === null ? "" : String(editing[name])]),
            ),
            ...Object.fromEntries(catalogue.map(({ code }) => [code, editing.features.includes(code)])),
          }}
          onClose={() => setEditing(null)}
          onSubmit={async (values) => {
            const included = catalogue.map(({ code }) => code).filter((code) => values[code]);
            await api.put(`${path}/${editing.id}`, { ...values, features: included, version: editing.version });
            setEditing(null);
            await refresh();
          }}
        />
      )}
    </div>
  );
}
