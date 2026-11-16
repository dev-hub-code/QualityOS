"use client";

import { useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { PageHeader, QueryState, RecordLink, StatusBadge } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import type { FieldDef } from "@/lib/record-form";
import type { CustomerTicket } from "@/lib/types";

const PATH = "/support/tickets";
const FIELDS: FieldDef[] = [
  { name: "subject", label: "Subject", required: true },
  { name: "message", label: "How can we help?", type: "textarea", required: true },
];
const time = (value: string) => new Date(value).toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" });

export default function SupportPage() {
  const router = useRouter();
  const managesOrganisation = usePermission("TENANT_MANAGE");
  const [raising, setRaising] = useState(false);
  const { data, error } = useQuery({ queryKey: [PATH], queryFn: () => api.get<CustomerTicket[]>(PATH) });
  return (
    <div className="space-y-6">
      <PageHeader
        title="Support"
        subtitle={
          managesOrganisation
            ? "Ask the ProductionOS team for help. You see every request your organisation raised."
            : "Ask the ProductionOS team for help. You see the requests you raised."
        }
      >
        <Button onClick={() => setRaising(true)}>New request</Button>
      </PageHeader>
      {!data ? (
        <QueryState error={error} />
      ) : data.length === 0 ? (
        <p className="text-sm text-muted-foreground">No requests yet.</p>
      ) : (
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Subject</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Raised by</TableHead>
                <TableHead>Updated</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.map((ticket) => (
                <TableRow key={ticket.id}>
                  <TableCell className="max-w-72 truncate font-medium">
                    <RecordLink href={`/support/${ticket.id}`}>{ticket.subject}</RecordLink>
                  </TableCell>
                  <TableCell>
                    <StatusBadge status={ticket.status} />
                  </TableCell>
                  <TableCell>{ticket.openedBy}</TableCell>
                  <TableCell className="whitespace-nowrap">{time(ticket.updatedAt)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      {raising && (
        <RecordDialog
          title="New support request"
          fields={FIELDS}
          onClose={() => setRaising(false)}
          onSubmit={async (values) => {
            const created = await api.post<{ id: string }>(PATH, values);
            router.push(`/support/${created.id}`);
          }}
        />
      )}
    </div>
  );
}
