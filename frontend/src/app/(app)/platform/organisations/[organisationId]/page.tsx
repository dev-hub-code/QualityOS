"use client";

import { useQuery } from "@tanstack/react-query";
import { useParams } from "next/navigation";
import { formatDate, formatMoney } from "@/components/billing/subscription-banner";
import { Details, PageHeader, QueryState, Section } from "@/components/records/parts";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { api } from "@/lib/api";
import type { OrganisationDetail } from "@/lib/types";

const megabytes = (bytes: number) => `${(bytes / 1_048_576).toFixed(1)} MB`;
const used = (count: number, limit: number | null) => (limit === null ? String(count) : `${count} of ${limit}`);

export default function PlatformOrganisationPage() {
  const { organisationId } = useParams<{ organisationId: string }>();
  const path = `/platform/organisations/${organisationId}`;
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<OrganisationDetail>(path) });
  if (!data) return <QueryState error={error} />;
  const { subscription, usage } = data;
  const outstanding = data.invoices
    .filter((invoice) => invoice.status === "ISSUED")
    .reduce((sum, invoice) => sum + invoice.total, 0);
  return (
    <div className="space-y-6">
      <PageHeader title={data.name} subtitle="Figures only. The content of this organisation's quality records is not shown here." />
      <div className="grid gap-4 xl:grid-cols-2">
        <Section title="Profile">
          <Details
            items={[
              ["Legal name", data.legalName ?? "Not recorded"],
              ["Email", data.contactEmail ?? "Not recorded"],
              ["GSTIN", data.gstin ?? "Not recorded"],
              ["Country", data.countryCode],
              ["Time zone", data.timezone],
              ["Registered", formatDate(data.registeredAt)],
            ]}
          />
        </Section>
        <Section title="Subscription">
          <Details
            items={[
              ["Plan", subscription.planName],
              ["State", subscription.state.replace("_", " ").toLowerCase()],
              ["Billing", subscription.billingCycle?.toLowerCase() ?? "Not yet billed"],
              ["Ends", formatDate(subscription.endsAt)],
              ["Read-only from", formatDate(subscription.readOnlyFrom)],
              ["Cancelled", subscription.cancelledAt ? formatDate(subscription.cancelledAt) : "No"],
            ]}
          />
        </Section>
        <Section title="Usage">
          <Details
            items={[
              ["Users", used(subscription.users, subscription.maxUsers)],
              ["Facilities", used(subscription.facilities, subscription.maxFacilities)],
              ["Inspections", usage.inspections],
              ["NCRs", usage.ncrs],
              ["CAPAs", usage.capas],
              ["Attachments", `${usage.attachments} (${megabytes(usage.attachmentBytes)})`],
            ]}
          />
        </Section>
        <Section title="Recent activity">
          {data.activity.length === 0 ? (
            <p className="text-sm text-muted-foreground">Nothing recorded yet.</p>
          ) : (
            <ul className="space-y-1 text-sm">
              {data.activity.map(({ action, entityType, occurredAt }, index) => (
                <li key={index}>
                  {formatDate(occurredAt)} {action.replaceAll("_", " ").toLowerCase()} ({entityType})
                </li>
              ))}
            </ul>
          )}
        </Section>
      </div>
      <Section title={`Invoices (outstanding ${formatMoney(outstanding)})`}>
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Invoice</TableHead>
                <TableHead>Plan</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Due</TableHead>
                <TableHead className="text-right">Total</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.invoices.map((invoice) => (
                <TableRow key={invoice.id}>
                  <TableCell>{invoice.invoiceNumber}</TableCell>
                  <TableCell>{invoice.planName}</TableCell>
                  <TableCell>{invoice.status.toLowerCase()}</TableCell>
                  <TableCell>{formatDate(invoice.dueOn)}</TableCell>
                  <TableCell className="text-right">{formatMoney(invoice.total)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </Section>
      <Section title="Payments">
        {data.payments.length === 0 ? (
          <p className="text-sm text-muted-foreground">No payments yet.</p>
        ) : (
          <ul className="space-y-1 text-sm">
            {data.payments.map((payment) => (
              <li key={payment.id}>
                {formatDate(payment.occurredAt)} {formatMoney(payment.amount)} {payment.status.toLowerCase()},{" "}
                reference {payment.providerReference}
                {payment.failureReason && `: ${payment.failureReason}`}
              </li>
            ))}
          </ul>
        )}
      </Section>
    </div>
  );
}
