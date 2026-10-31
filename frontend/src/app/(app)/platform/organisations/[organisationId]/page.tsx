"use client";

import { useQuery } from "@tanstack/react-query";
import { useParams } from "next/navigation";
import { useState } from "react";
import { formatDate, formatMoney } from "@/components/billing/subscription-banner";
import { EXTEND_FIELDS, ReasonDialog, RecordPaymentDialog } from "@/components/platform/billing-dialogs";
import { Details, PageHeader, QueryState, Section, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button, buttonVariants } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import type { Invoice, OrganisationDetail } from "@/lib/types";

const megabytes = (bytes: number) => `${(bytes / 1_048_576).toFixed(1)} MB`;
const used = (count: number, limit: number | null) => (limit === null ? String(count) : `${count} of ${limit}`);

export default function PlatformOrganisationPage() {
  const billing = usePermission("PLATFORM_BILLING_MANAGE");
  const support = usePermission("PLATFORM_CUSTOMER_MANAGE");
  const { organisationId } = useParams<{ organisationId: string }>();
  const path = `/platform/organisations/${organisationId}`;
  const refresh = useRefresh();
  const [action, setAction] = useState<[string, string] | null>(null);
  const [extending, setExtending] = useState(false);
  const [paying, setPaying] = useState<Invoice | null>(null);
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<OrganisationDetail>(path) });
  if (!data) return <QueryState error={error} />;
  const { subscription, usage } = data;
  const trialRunning = subscription.state === "TRIAL";
  const outstanding = data.invoices
    .filter((invoice) => invoice.status === "ISSUED")
    .reduce((sum, invoice) => sum + invoice.total, 0);
  return (
    <div className="space-y-6">
      <PageHeader title={data.name} subtitle="Figures only. The content of this organisation's quality records is not shown here.">
        {(billing || support) && (
          <Button variant="outline" onClick={() => setExtending(true)}>
            Extend
          </Button>
        )}
        {(billing || support) && trialRunning && (
          <Button variant="outline" onClick={() => setAction(["End trial", `${path}/trial/end`])}>
            End trial
          </Button>
        )}
        {billing &&
          (subscription.cancelledAt ? (
            <Button variant="outline" onClick={() => setAction(["Resume subscription", `${path}/resume`])}>
              Resume
            </Button>
          ) : (
            <Button variant="outline" onClick={() => setAction(["Cancel subscription", `${path}/cancel`])}>
              Cancel subscription
            </Button>
          ))}
        {support &&
          (data.suspendedAt ? (
            <Button onClick={() => setAction(["Reactivate organisation", `${path}/reactivate`])}>Reactivate</Button>
          ) : (
            <Button variant="destructive" onClick={() => setAction(["Suspend organisation", `${path}/suspend`])}>
              Suspend
            </Button>
          ))}
      </PageHeader>
      {data.suspendedAt && (
        <p role="alert" className="rounded-md border border-destructive/40 p-3 text-sm">
          Suspended on {formatDate(data.suspendedAt)}: {data.suspensionReason}. Members cannot sign in. Their records are
          untouched.
        </p>
      )}
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
                <TableHead />
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
                  <TableCell className="space-x-2 whitespace-nowrap text-right">
                    {billing && invoice.status === "ISSUED" && (
                      <Button size="sm" variant="outline" onClick={() => setPaying(invoice)}>
                        Record payment
                      </Button>
                    )}
                    <a
                      className={buttonVariants({ size: "sm", variant: "outline" })}
                      href={`/api/v1${path}/invoices/${invoice.id}/pdf`}
                    >
                      PDF
                    </a>
                  </TableCell>
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
                {formatDate(payment.paidOn ?? payment.occurredAt)} {formatMoney(payment.amount)}{" "}
                {payment.status.toLowerCase()},{" "}
                reference {payment.providerReference}
                {payment.failureReason && `: ${payment.failureReason}`}
              </li>
            ))}
          </ul>
        )}
      </Section>
      {action && <ReasonDialog title={action[0]} path={action[1]} onClose={() => setAction(null)} />}
      {extending && (
        <RecordDialog
          title="Extend trial or subscription"
          fields={EXTEND_FIELDS}
          onClose={() => setExtending(false)}
          onSubmit={async (values) => {
            await api.post(`${path}/extend`, values);
            await refresh();
          }}
        />
      )}
      {paying && (
        <RecordPaymentDialog
          tenantId={data.tenantId}
          invoiceId={paying.id}
          invoiceNumber={paying.invoiceNumber}
          amount={paying.total}
          onClose={() => setPaying(null)}
        />
      )}
    </div>
  );
}
