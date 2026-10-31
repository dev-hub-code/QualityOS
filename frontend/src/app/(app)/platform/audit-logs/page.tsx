"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { PageHeader, QueryState } from "@/components/records/parts";
import { Button, buttonVariants } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { api } from "@/lib/api";
import type { PlatformAuditEntry, PlatformAuditList } from "@/lib/types";

const PAGE_SIZE = 25;
const time = (value: string) => new Date(value).toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" });

function Entry({ entry, onClose }: { entry: PlatformAuditEntry; onClose: () => void }) {
  const rows: [string, string | null][] = [
    ["Time", time(entry.occurredAt)],
    ["Actor", entry.actor],
    ["Action", entry.action],
    ["Target", `${entry.entityType} ${entry.entityId ?? ""}`],
    ["Organisation", entry.organisation],
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
        {(["before", "after"] as const).map((side) => entry[side] && (
          <div key={side}>
            <p className="text-sm font-medium">{side === "before" ? "Before" : "After"}</p>
            <pre className="max-h-48 overflow-auto rounded-md bg-muted p-2 text-xs">{JSON.stringify(entry[side], null, 2)}</pre>
          </div>
        ))}
      </DialogContent>
    </Dialog>
  );
}

function AuditLog() {
  const params = useSearchParams();
  const [filters, setFilters] = useState({ q: params.get("q") ?? "", action: "", from: "", to: "" });
  const [page, setPage] = useState(0);
  const [viewing, setViewing] = useState<PlatformAuditEntry | null>(null);
  const filter = new URLSearchParams(Object.entries(filters).filter(([, value]) => value)).toString();
  const path = `/platform/audit-logs?${filter}${filter ? "&" : ""}page=${page}&size=${PAGE_SIZE}`;
  const { data, error } = useQuery({
    queryKey: [path],
    queryFn: () => api.get<PlatformAuditList>(path),
    placeholderData: keepPreviousData,
  });
  const set = (name: keyof typeof filters, value: string) => {
    setFilters({ ...filters, [name]: value });
    setPage(0);
  };
  return (
    <div className="space-y-6">
      <PageHeader title="Audit log" subtitle="What operators did. Entries cannot be edited or deleted, and sensitive values are hidden.">
        <a className={buttonVariants({ variant: "outline" })} href={`/api/v1/platform/audit-logs/export?${filter}`}>
          Export CSV
        </a>
      </PageHeader>
      <div className="flex flex-wrap items-end gap-3">
        <div className="space-y-1.5">
          <Label htmlFor="audit-search">Search</Label>
          <Input id="audit-search" placeholder="Actor, organisation or ID" value={filters.q} onChange={(e) => set("q", e.target.value)} />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="audit-action">Action</Label>
          <Input id="audit-action" placeholder="OPERATOR_ADDED" value={filters.action} onChange={(e) => set("action", e.target.value.toUpperCase())} />
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
                  <TableHead>Organisation</TableHead>
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
                      <div className="text-sm text-muted-foreground">{entry.actor}</div>
                    </TableCell>
                    <TableCell className="max-w-48 truncate">{entry.organisation}</TableCell>
                    <TableCell className="max-w-48 truncate">{entry.reason}</TableCell>
                    <TableCell className="text-right">
                      <Button size="sm" variant="outline" onClick={() => setViewing(entry)}>View</Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <div className="flex items-center justify-between text-sm">
            <span>{data.total} entries</span>
            <span className="space-x-2">
              <Button size="sm" variant="outline" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</Button>
              <Button size="sm" variant="outline" disabled={(page + 1) * PAGE_SIZE >= data.total} onClick={() => setPage(page + 1)}>Next</Button>
            </span>
          </div>
        </>
      )}
      {viewing && <Entry entry={viewing} onClose={() => setViewing(null)} />}
    </div>
  );
}

export default function PlatformAuditLogPage() {
  return (
    <Suspense>
      <AuditLog />
    </Suspense>
  );
}
