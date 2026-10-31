"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { REASON_FIELD } from "@/components/platform/billing-dialogs";
import { PageHeader, QueryState, StatusBadge, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useMe } from "@/hooks/use-me";
import { api } from "@/lib/api";
import type { PlatformEmail, PlatformEmailList } from "@/lib/types";

const PAGE_SIZE = 25;
const time = (value: string) => new Date(value).toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" });

export default function PlatformEmailLogsPage() {
  const refresh = useRefresh();
  const { data: me } = useMe();
  const manage = me?.permissions.includes("PLATFORM_CUSTOMER_MANAGE") ?? false;
  const [q, setQ] = useState("");
  const [state, setState] = useState("");
  const [page, setPage] = useState(0);
  const [retrying, setRetrying] = useState<PlatformEmail | null>(null);
  const filter = new URLSearchParams({
    ...(q && { q }),
    ...(state === "RETRYABLE" ? { retryable: "true" } : state ? { status: state } : {}),
  }).toString();
  const path = `/platform/email-logs?${filter}${filter ? "&" : ""}page=${page}&size=${PAGE_SIZE}`;
  const { data, error } = useQuery({
    queryKey: [path],
    queryFn: () => api.get<PlatformEmailList>(path),
    placeholderData: keepPreviousData,
  });
  return (
    <div className="space-y-6">
      <PageHeader
        title="Email log"
        subtitle="One row per email. Only a notification that gave up can be retried. Account emails, such as reset links, cannot, because their links have expired."
      />
      <div className="flex flex-wrap items-end gap-3">
        <div className="space-y-1.5">
          <Label htmlFor="email-search">Recipient or template</Label>
          <Input id="email-search" value={q} onChange={(event) => { setQ(event.target.value); setPage(0); }} />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="email-state">Delivery</Label>
          <select
            id="email-state"
            className="h-9 rounded-md border bg-background px-2 text-sm"
            value={state}
            onChange={(event) => { setState(event.target.value); setPage(0); }}
          >
            <option value="">Any</option>
            <option value="SENT">Sent</option>
            <option value="FAILED">Failed</option>
            <option value="RETRYABLE">Can be retried</option>
          </select>
        </div>
      </div>
      {!data ? (
        <QueryState error={error} />
      ) : data.items.length === 0 ? (
        <p className="text-sm text-muted-foreground">No emails match.</p>
      ) : (
        <>
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Recipient</TableHead>
                  <TableHead>Template</TableHead>
                  <TableHead>Delivery</TableHead>
                  <TableHead>Attempts</TableHead>
                  <TableHead>Last attempt</TableHead>
                  <TableHead>Error</TableHead>
                  <TableHead className="text-right">Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {data.items.map((email) => (
                  <TableRow key={email.id}>
                    <TableCell>
                      <div>{email.recipient}</div>
                      <div className="text-sm text-muted-foreground">{email.organisation ?? "Account email"}</div>
                    </TableCell>
                    <TableCell>{email.template}</TableCell>
                    <TableCell><StatusBadge status={email.status} /></TableCell>
                    <TableCell>{email.attempts}</TableCell>
                    <TableCell className="whitespace-nowrap">{time(email.lastAttempt)}</TableCell>
                    <TableCell>{email.errorType ?? "None"}</TableCell>
                    <TableCell className="text-right">
                      {manage && email.retryable && (
                        <Button size="sm" variant="outline" onClick={() => setRetrying(email)}>Retry</Button>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <div className="flex items-center justify-between text-sm">
            <span>{data.total} emails</span>
            <span className="space-x-2">
              <Button size="sm" variant="outline" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</Button>
              <Button size="sm" variant="outline" disabled={(page + 1) * PAGE_SIZE >= data.total} onClick={() => setPage(page + 1)}>Next</Button>
            </span>
          </div>
        </>
      )}
      {retrying && (
        <RecordDialog
          title={`Retry email to ${retrying.recipient}`}
          fields={[REASON_FIELD]}
          onClose={() => setRetrying(null)}
          onSubmit={async (values) => {
            await api.post(
              `/platform/organisations/${retrying.tenantId}/notifications/${retrying.notificationId}/retry-email`,
              values,
            );
            await refresh();
          }}
        />
      )}
    </div>
  );
}
