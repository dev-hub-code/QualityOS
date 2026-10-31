"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { PageHeader, QueryState, StatusBadge } from "@/components/records/parts";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { api } from "@/lib/api";
import type { PlatformNoticeList } from "@/lib/types";

const PAGE_SIZE = 25;
const TYPES: Record<string, string> = {
  SUBSCRIPTION_EXPIRY: "Trial or subscription ending",
  INVOICE_OVERDUE: "Invoice overdue",
  PAYMENT_RECEIVED: "Payment recorded",
  PLAN_CHANGED: "Plan changed",
};

export default function PlatformNotificationsPage() {
  const [type, setType] = useState("");
  const [page, setPage] = useState(0);
  const path = `/platform/notifications?${type ? `type=${type}&` : ""}page=${page}&size=${PAGE_SIZE}`;
  const { data, error } = useQuery({
    queryKey: [path],
    queryFn: () => api.get<PlatformNoticeList>(path),
    placeholderData: keepPreviousData,
  });
  return (
    <div className="space-y-6">
      <PageHeader
        title="Notifications"
        subtitle="Billing notices sent to customers. A reminder goes once for each stage. Quality notifications stay private to each organisation."
      />
      <div className="space-y-1.5">
        <Label htmlFor="notice-type">Type</Label>
        <select
          id="notice-type"
          className="h-9 rounded-md border bg-background px-2 text-sm"
          value={type}
          onChange={(event) => { setType(event.target.value); setPage(0); }}
        >
          <option value="">Any</option>
          {Object.entries(TYPES).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </div>
      {!data ? (
        <QueryState error={error} />
      ) : data.items.length === 0 ? (
        <p className="text-sm text-muted-foreground">No notices match.</p>
      ) : (
        <>
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Sent</TableHead>
                  <TableHead>Type</TableHead>
                  <TableHead>Organisation</TableHead>
                  <TableHead>Recipient</TableHead>
                  <TableHead>Email</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {data.items.map((notice) => (
                  <TableRow key={notice.id}>
                    <TableCell className="whitespace-nowrap">
                      {new Date(notice.sentAt).toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" })}
                    </TableCell>
                    <TableCell>{TYPES[notice.type] ?? notice.type}</TableCell>
                    <TableCell>{notice.organisation}</TableCell>
                    <TableCell>{notice.recipient}</TableCell>
                    <TableCell><StatusBadge status={notice.emailStatus} /></TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <div className="flex items-center justify-between text-sm">
            <span>{data.total} notices</span>
            <span className="space-x-2">
              <Button size="sm" variant="outline" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</Button>
              <Button size="sm" variant="outline" disabled={(page + 1) * PAGE_SIZE >= data.total} onClick={() => setPage(page + 1)}>Next</Button>
            </span>
          </div>
        </>
      )}
    </div>
  );
}
