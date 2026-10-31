"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useParams } from "next/navigation";
import { formatDate } from "@/components/billing/subscription-banner";
import { PageHeader, QueryState, Section } from "@/components/records/parts";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { api } from "@/lib/api";
import type { OrganisationList, Plan, PlanVersion } from "@/lib/types";

export default function PlatformPlanPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/platform/plans/${id}`;
  const plans = useQuery({ queryKey: ["/platform/plans"], queryFn: () => api.get<Plan[]>("/platform/plans") });
  const versions = useQuery({ queryKey: [path, "versions"], queryFn: () => api.get<PlanVersion[]>(`${path}/versions`) });
  const plan = plans.data?.find((candidate) => candidate.id === id);
  const subscribers = useQuery({
    queryKey: ["/platform/organisations", "plan", plan?.code],
    queryFn: () => api.get<OrganisationList>(`/platform/organisations?size=100&plan=${plan?.code}`),
    enabled: plan !== undefined,
  });
  if (!plan) return <QueryState error={plans.error ?? (plans.data ? new Error("This record does not exist.") : null)} />;
  return (
    <div className="space-y-6">
      <PageHeader title={plan.name} subtitle={`${plan.code}. ${plan.description}`} />
      <Section title={`Organisations on this plan (${subscribers.data?.total ?? 0})`}>
        {subscribers.data?.items.length === 0 ? (
          <p className="text-sm text-muted-foreground">No organisation is on this plan.</p>
        ) : (
          <ul className="space-y-1 text-sm">
            {subscribers.data?.items.map(({ tenantId, organizationName, subscription }) => (
              <li key={tenantId}>
                <Link className="underline" href={`/platform/organisations/${tenantId}`}>
                  {organizationName}
                </Link>{" "}
                {subscription.state.replace("_", " ").toLowerCase()}
              </li>
            ))}
          </ul>
        )}
      </Section>
      <Section title="History">
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Version</TableHead>
                <TableHead>Changed</TableHead>
                <TableHead>Reason</TableHead>
                <TableHead>Monthly</TableHead>
                <TableHead>Annual</TableHead>
                <TableHead>Features</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {versions.data?.map(({ version, changedAt, reason, snapshot }) => (
                <TableRow key={version}>
                  <TableCell>{version}</TableCell>
                  <TableCell>{formatDate(changedAt)}</TableCell>
                  <TableCell className="whitespace-normal">{reason}</TableCell>
                  <TableCell>{String(snapshot.monthlyPrice ?? "Custom")}</TableCell>
                  <TableCell>{String(snapshot.annualPrice ?? "Custom")}</TableCell>
                  <TableCell className="whitespace-normal">{(snapshot.features as string[]).join(", ")}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </Section>
    </div>
  );
}
