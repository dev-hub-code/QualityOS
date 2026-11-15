"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { PageHeader, QueryState } from "@/components/records/parts";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { api, type PageResponse } from "@/lib/api";
import type { AuditEntry } from "@/lib/types";

const PAGE_SIZE = 25;
const time = (value: string) => new Date(value).toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" });

function Entry({ entry, onClose }: { entry: AuditEntry; onClose: () => void }) {
  const rows: [string, string | null][] = [
    ["Time", time(entry.occurredAt)],
    ["Who", entry.actor ?? (entry.userId ? "Former member" : "The system")],
    ["Action", entry.action],
    ["Target", `${entry.entityType} ${entry.entityId ?? ""}`.trim()],
    ["Reason", entry.reason],
    ["Correlation ID", entry.correlationId],
    ["IP address", entry.ipAddress],
  ];
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Audit entry</DialogTitle>
        </DialogHeader>
        <dl className="grid grid-cols-[9rem_1fr] gap-x-3 gap-y-1 text-sm">
          {rows.map(([label, value]) => (
            <div key={label} className="contents">
              <dt className="text-muted-foreground">{label}</dt>
              <dd className="break-all">{value || "None"}</dd>
            </div>
          ))}
        </dl>
        {(["before", "after"] as const).map(
          (side) =>
            entry[side] && (
              <div key={side}>
                <p className="text-sm font-medium">{side === "before" ? "Before" : "After"}</p>
                <pre className="max-h-48 overflow-auto rounded-md bg-muted p-2 text-xs">{JSON.stringify(entry[side], null, 2)}</pre>
              </div>
            ),
        )}
      </DialogContent>
    </Dialog>
  );
}

/** What the organisation's members did. Account events such as password resets have no organisation, so they are not here. */
export default function AuditLogPage() {
  const [filters, setFilters] = useState({ q: "", action: "", from: "", to: "" });
  const [page, setPage] = useState(0);
  const [viewing, setViewing] = useState<AuditEntry | null>(null);
  const filter = new URLSearchParams(Object.entries(filters).filter(([, value]) => value)).toString();
  const path = `/audit-logs?${filter}${filter ? "&" : ""}page=${page}&size=${PAGE_SIZE}`;
  const { data, error } = useQuery({
    queryKey: [path],
    queryFn: () => api.get<PageResponse<AuditEntry>>(path),
    placeholderData: keepPreviousData,
  });
  const set = (name: keyof typeof filters, value: string) => {
    setFilters({ ...filters, [name]: value });
    setPage(0);
  };
  return (
    <div className="space-y-6">
      <PageHeader
        title="Audit log"
        subtitle="Every change made in this organisation, by whom and when. Entries cannot be edited or deleted."
      />
      <div className="flex flex-wrap items-end gap-3">
        <div className="space-y-1.5">
          <Label htmlFor="audit-search">Search</Label>
          <Input id="audit-search" placeholder="Person, record type or ID" value={filters.q} onChange={(e) => set("q", e.target.value)} />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="audit-action">Action</Label>
          <Input id="audit-action" placeholder="NCR_RAISED" value={filters.action} onChange={(e) => set("action", e.target.value.toUpperCase())} />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="audit-from">From</Label>
          <Input id="audit-from" type="date" value={filters.from} onChange={(e) => set("from", e.target.value)} />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="audit-to">To</Label>
          <Input id="audit-to" type="date" value={filters.to} onChange={(e) => set("to", e.target.value)} />
        </div>
      </div>
      {!data ? (
        <QueryState error={error} />
      ) : data.items.length === 0 ? (
        <p className="text-sm text-muted-foreground">No entries match.</p>
      ) : (
        <>
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Time</TableHead>
                  <TableHead>Action</TableHead>
                  <TableHead>Target</TableHead>
                  <TableHead>Reason</TableHead>
                  <TableHead className="text-right">Details</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {data.items.map((entry) => (
                  <TableRow key={entry.id}>
                    <TableCell className="whitespace-nowrap">{time(entry.occurredAt)}</TableCell>
                    <TableCell className="max-w-80 whitespace-normal break-all">
                      <div className="font-medium">{entry.action}</div>
                      <div className="text-sm text-muted-foreground">{entry.actor ?? (entry.userId ? "Former member" : "The system")}</div>
                    </TableCell>
                    <TableCell className="max-w-56 truncate">
                      {entry.entityType}
                      {entry.entityId && <span className="text-muted-foreground"> {entry.entityId}</span>}
                    </TableCell>
                    <TableCell className="max-w-48 truncate">{entry.reason}</TableCell>
                    <TableCell className="text-right">
                      <Button size="sm" variant="outline" onClick={() => setViewing(entry)}>
                        View
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <div className="flex items-center justify-between text-sm">
            <span>{data.totalItems} entries</span>
            <span className="space-x-2">
              <Button size="sm" variant="outline" disabled={page === 0} onClick={() => setPage(page - 1)}>
                Previous
              </Button>
              <Button size="sm" variant="outline" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>
                Next
              </Button>
            </span>
          </div>
        </>
      )}
      {viewing && <Entry entry={viewing} onClose={() => setViewing(null)} />}
    </div>
  );
}
