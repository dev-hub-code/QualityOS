"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { formatDate } from "@/components/billing/subscription-banner";
import { Details, PageHeader, QueryState, RecordLink, Section, StatusBadge, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import type { FieldDef } from "@/lib/record-form";
import type { OrganisationDetail, OrganisationList, SupportError, SupportTicket, SupportTicketList, TicketNote } from "@/lib/types";

const STATUSES: Record<string, string> = {
  OPEN: "Open",
  IN_PROGRESS: "In progress",
  WAITING_FOR_CUSTOMER: "Waiting for customer",
  RESOLVED: "Resolved",
  CLOSED: "Closed",
};
const OPEN_FIELDS: FieldDef[] = [
  { name: "subject", label: "Subject", required: true },
  { name: "note", label: "First internal note", type: "textarea", required: true },
];
const NOTE_FIELDS: FieldDef[] = [{ name: "body", label: "Internal note", type: "textarea", required: true }];
const time = (value: string) => new Date(value).toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" });

function TicketDialog({ ticket, onClose }: { ticket: SupportTicket; onClose: () => void }) {
  const refresh = useRefresh();
  const manage = usePermission("PLATFORM_CUSTOMER_MANAGE");
  const [adding, setAdding] = useState(false);
  const [failure, setFailure] = useState("");
  const path = `/platform/support/tickets/${ticket.id}`;
  const { data, error } = useQuery({ queryKey: [`${path}/notes`], queryFn: () => api.get<TicketNote[]>(`${path}/notes`) });
  const closed = ticket.status === "CLOSED";
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{ticket.subject}</DialogTitle>
        </DialogHeader>
        <p className="text-sm text-muted-foreground">{ticket.organisation}. Notes are private to operators.</p>
        {manage && !closed && (
          <div className="space-y-1.5">
            <Label htmlFor="ticket-status">Status</Label>
            <select
              id="ticket-status"
              className="h-9 rounded-md border bg-background px-2 text-sm"
              defaultValue={ticket.status}
              onChange={async (event) => {
                setFailure("");
                try {
                  await api.put(`${path}/status`, { status: event.target.value, version: ticket.version });
                  await refresh();
                  onClose();
                } catch (cause) {
                  setFailure(cause instanceof Error ? cause.message : "Something went wrong.");
                }
              }}
            >
              {Object.entries(STATUSES).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </select>
          </div>
        )}
        {failure && <p role="alert" className="text-sm text-destructive">{failure}</p>}
        {!data ? (
          <QueryState error={error} />
        ) : (
          <ul className="max-h-72 space-y-3 overflow-auto">
            {data.map((note) => (
              <li key={note.id} className="rounded-md border p-2 text-sm">
                <p className="text-muted-foreground">{note.author}, {time(note.createdAt)}</p>
                <p className="whitespace-pre-wrap">{note.body}</p>
              </li>
            ))}
          </ul>
        )}
        {manage && !closed && <Button variant="outline" onClick={() => setAdding(true)}>Add note</Button>}
        {adding && (
          <RecordDialog
            title="Add internal note"
            fields={NOTE_FIELDS}
            onClose={() => setAdding(false)}
            onSubmit={async (values) => {
              await api.post(`${path}/notes`, values);
              await refresh();
            }}
          />
        )}
      </DialogContent>
    </Dialog>
  );
}

function Tickets({ tenantId, status, onView }: { tenantId?: string; status?: string; onView: (ticket: SupportTicket) => void }) {
  const params = new URLSearchParams({ ...(tenantId && { tenantId }), ...(status && { status }), size: "50" });
  const path = `/platform/support/tickets?${params}`;
  const { data, error } = useQuery({
    queryKey: [path],
    queryFn: () => api.get<SupportTicketList>(path),
    placeholderData: keepPreviousData,
  });
  if (!data) return <QueryState error={error} />;
  if (data.items.length === 0) return <p className="text-sm text-muted-foreground">No tickets.</p>;
  return (
    <div className="overflow-x-auto">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Subject</TableHead>
            <TableHead>Organisation</TableHead>
            <TableHead>Status</TableHead>
            <TableHead>Updated</TableHead>
            <TableHead className="text-right">Details</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {data.items.map((ticket) => (
            <TableRow key={ticket.id}>
              <TableCell className="max-w-64 truncate font-medium">{ticket.subject}</TableCell>
              <TableCell className="max-w-48 truncate">{ticket.organisation}</TableCell>
              <TableCell><StatusBadge status={ticket.status} /></TableCell>
              <TableCell className="whitespace-nowrap">{time(ticket.updatedAt)}</TableCell>
              <TableCell className="text-right">
                <Button size="sm" variant="outline" onClick={() => onView(ticket)}>View</Button>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

function Customer({ tenantId, onView }: { tenantId: string; onView: (ticket: SupportTicket) => void }) {
  const refresh = useRefresh();
  const manage = usePermission("PLATFORM_CUSTOMER_MANAGE");
  const [opening, setOpening] = useState(false);
  const path = `/platform/organisations/${tenantId}`;
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<OrganisationDetail>(path) });
  const errorsPath = `/platform/support/organisations/${tenantId}/errors`;
  const { data: errors } = useQuery({ queryKey: [errorsPath], queryFn: () => api.get<SupportError[]>(errorsPath) });
  if (!data) return <QueryState error={error} />;
  const outstanding = data.invoices.filter((invoice) => invoice.status === "ISSUED");
  return (
    <div className="space-y-6">
      <Section
        title={data.name}
        action={<RecordLink href={`/platform/organisations/${tenantId}`}>Open organisation</RecordLink>}
      >
        <Details
          items={[
            ["Contact email", data.contactEmail ?? "Not recorded"],
            ["GSTIN", data.gstin ?? "Not recorded"],
            ["Plan", data.subscription.planName],
            ["Subscription", `${data.subscription.state.replace("_", " ").toLowerCase()}, to ${formatDate(data.subscription.endsAt)}`],
            ["Open invoices", outstanding.length],
            ["Users", data.subscription.users],
            ["Facilities", data.subscription.facilities],
            ["State", data.suspendedAt ? "Suspended" : "Active"],
          ]}
        />
      </Section>
      <Section title="Tickets" action={manage ? <Button size="sm" onClick={() => setOpening(true)}>New ticket</Button> : undefined}>
        <Tickets tenantId={tenantId} onView={onView} />
      </Section>
      <Section title="Failed emails in the last 30 days">
        {!errors || errors.length === 0 ? (
          <p className="text-sm text-muted-foreground">None.</p>
        ) : (
          <ul className="space-y-1 text-sm">
            {errors.map((failure) => (
              <li key={`${failure.occurredAt}${failure.template}`}>
                {time(failure.occurredAt)}: {failure.template}, {failure.errorType ?? "unknown error"}
              </li>
            ))}
          </ul>
        )}
      </Section>
      {opening && (
        <RecordDialog
          title={`New ticket for ${data.name}`}
          fields={OPEN_FIELDS}
          onClose={() => setOpening(false)}
          onSubmit={async (values) => {
            await api.post("/platform/support/tickets", { ...values, tenantId });
            await refresh();
          }}
        />
      )}
    </div>
  );
}

export default function PlatformSupportPage() {
  const [q, setQ] = useState("");
  const [tenantId, setTenantId] = useState("");
  const [status, setStatus] = useState("");
  const [viewing, setViewing] = useState<SupportTicket | null>(null);
  const path = `/platform/organisations?q=${encodeURIComponent(q)}&size=8`;
  const { data: found } = useQuery({
    queryKey: [path],
    queryFn: () => api.get<OrganisationList>(path),
    enabled: q.trim().length > 1,
  });
  return (
    <div className="space-y-6">
      <PageHeader
        title="Support console"
        subtitle="Account and billing facts only. Support cannot open a customer's quality records, and tickets and notes are never shown to customers."
      />
      <div className="max-w-md space-y-1.5">
        <Label htmlFor="support-search">Find an organisation</Label>
        <Input id="support-search" placeholder="Name, email or GSTIN" value={q} onChange={(event) => setQ(event.target.value)} />
      </div>
      {q.trim().length > 1 && found && (
        <ul className="max-w-md divide-y rounded-md border">
          {found.items.map((row) => (
            <li key={row.tenantId}>
              <button
                type="button"
                className="w-full px-3 py-2 text-left text-sm hover:bg-muted"
                onClick={() => {
                  setTenantId(row.tenantId);
                  setQ("");
                }}
              >
                {row.organizationName}
              </button>
            </li>
          ))}
          {found.items.length === 0 && <li className="px-3 py-2 text-sm text-muted-foreground">No organisations match.</li>}
        </ul>
      )}
      {tenantId ? (
        <Customer key={tenantId} tenantId={tenantId} onView={setViewing} />
      ) : (
        <Section title="All tickets">
          <div className="mb-3 space-y-1.5">
            <Label htmlFor="ticket-filter">Status</Label>
            <select
              id="ticket-filter"
              className="h-9 rounded-md border bg-background px-2 text-sm"
              value={status}
              onChange={(event) => setStatus(event.target.value)}
            >
              <option value="">Any</option>
              {Object.entries(STATUSES).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </select>
          </div>
          <Tickets status={status} onView={setViewing} />
        </Section>
      )}
      {viewing && <TicketDialog key={viewing.id} ticket={viewing} onClose={() => setViewing(null)} />}
    </div>
  );
}
