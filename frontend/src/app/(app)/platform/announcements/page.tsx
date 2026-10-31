"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { formatDate } from "@/components/billing/subscription-banner";
import { REASON_FIELD } from "@/components/platform/billing-dialogs";
import { PageHeader, QueryState, StatusBadge, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import type { FieldDef } from "@/lib/record-form";
import type { PlatformAnnouncement } from "@/lib/types";

const PATH = "/platform/announcements";
const AUDIENCE: Record<string, string> = { ALL: "Everyone", TRIAL: "Organisations on trial", PAID: "Paying organisations" };
const FIELDS: FieldDef[] = [
  { name: "title", label: "Title", required: true },
  { name: "message", label: "Message", type: "textarea", required: true },
  {
    name: "severity",
    label: "Severity",
    type: "select",
    required: true,
    options: [
      { value: "INFO", label: "Information" },
      { value: "WARNING", label: "Warning" },
      { value: "CRITICAL", label: "Critical: cannot be dismissed" },
    ],
  },
  {
    name: "audience",
    label: "Audience",
    type: "select",
    required: true,
    options: Object.entries(AUDIENCE).map(([value, label]) => ({ value, label })),
  },
  { name: "startsOn", label: "Start date", type: "date", required: true },
  { name: "endsOn", label: "End date", type: "date", required: true },
  { name: "active", label: "Active", type: "checkbox" },
  REASON_FIELD,
];

export default function PlatformAnnouncementsPage() {
  const refresh = useRefresh();
  const manage = usePermission("PLATFORM_CUSTOMER_MANAGE");
  const [editing, setEditing] = useState<PlatformAnnouncement | "new" | null>(null);
  const { data, error } = useQuery({ queryKey: [PATH], queryFn: () => api.get<PlatformAnnouncement[]>(PATH) });
  const today = new Date().toLocaleDateString("en-CA");
  return (
    <div className="space-y-6">
      <PageHeader title="Announcements" subtitle="Shown as a banner to signed-in customers between the two dates, in Indian Standard Time. No email is sent.">
        {manage && <Button onClick={() => setEditing("new")}>New announcement</Button>}
      </PageHeader>
      {!data ? (
        <QueryState error={error} />
      ) : data.length === 0 ? (
        <p className="text-sm text-muted-foreground">No announcements yet.</p>
      ) : (
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Title</TableHead>
                <TableHead>Severity</TableHead>
                <TableHead>Audience</TableHead>
                <TableHead>Dates</TableHead>
                <TableHead>State</TableHead>
                <TableHead className="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.map((item) => (
                <TableRow key={item.id}>
                  <TableCell className="max-w-64 truncate font-medium">{item.title}</TableCell>
                  <TableCell><StatusBadge status={item.severity} /></TableCell>
                  <TableCell>{AUDIENCE[item.audience]}</TableCell>
                  <TableCell className="whitespace-nowrap">{formatDate(item.startsOn)} to {formatDate(item.endsOn)}</TableCell>
                  <TableCell>{item.active ? "Active" : "Inactive"}</TableCell>
                  <TableCell className="text-right">
                    {manage && <Button size="sm" variant="outline" onClick={() => setEditing(item)}>Edit</Button>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      {editing && (
        <RecordDialog
          title={editing === "new" ? "New announcement" : "Edit announcement"}
          fields={FIELDS}
          record={editing === "new" ? { severity: "INFO", audience: "ALL", startsOn: today, endsOn: today, active: true } : { ...editing }}
          onClose={() => setEditing(null)}
          onSubmit={async (values) => {
            if (editing === "new") {
              await api.post(PATH, { ...values, version: 0 });
            } else {
              await api.put(`${PATH}/${editing.id}`, { ...values, version: editing.version });
            }
            await refresh();
          }}
        />
      )}
    </div>
  );
}
