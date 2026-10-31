"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useState } from "react";
import { formatDate } from "@/components/billing/subscription-banner";
import { PageHeader, QueryState } from "@/components/records/parts";
import { Button, buttonVariants } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { api } from "@/lib/api";
import type { UsageHistory, UsageList, UsageRow } from "@/lib/types";
import { storageText, usageText } from "@/lib/usage";

const PAGE_SIZE = 25;
const LEVEL = { OK: "Within limits", NEAR: "Near a limit", REACHED: "Limit reached" };

function History({ row, onClose }: { row: UsageRow; onClose: () => void }) {
  const path = `/platform/organisations/${row.tenantId}/usage/history`;
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<UsageHistory[]>(path) });
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Usage history for {row.name}</DialogTitle>
        </DialogHeader>
        {!data ? (
          <QueryState error={error} />
        ) : data.length === 0 ? (
          <p className="text-sm text-muted-foreground">No daily record yet. One is taken every six hours.</p>
        ) : (
          <div className="max-h-96 overflow-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Day</TableHead>
                  <TableHead>Users</TableHead>
                  <TableHead>Facilities</TableHead>
                  <TableHead>Storage</TableHead>
                  <TableHead>Inspections in month</TableHead>
                  <TableHead>Attachments</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {data.map((day) => (
                  <TableRow key={day.takenOn}>
                    <TableCell>{formatDate(day.takenOn)}</TableCell>
                    <TableCell>{day.users}</TableCell>
                    <TableCell>{day.facilities}</TableCell>
                    <TableCell>{storageText(day.storageBytes, null).replace(" (no limit)", "")}</TableCell>
                    <TableCell>{day.inspections}</TableCell>
                    <TableCell>{day.attachments}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

export default function PlatformUsagePage() {
  const [q, setQ] = useState("");
  const [alert, setAlert] = useState("");
  const [page, setPage] = useState(0);
  const [viewing, setViewing] = useState<UsageRow | null>(null);
  const filter = new URLSearchParams(Object.entries({ q, alert }).filter(([, value]) => value)).toString();
  const path = `/platform/usage?${filter}${filter ? "&" : ""}page=${page}&size=${PAGE_SIZE}`;
  const { data, error } = useQuery({
    queryKey: [path],
    queryFn: () => api.get<UsageList>(path),
    placeholderData: keepPreviousData,
  });
  return (
    <div className="space-y-6">
      <PageHeader
        title="Usage"
        subtitle="Closest to a limit first. A limit is reached at 100%, and near from 80%. Limits are enforced when something is added."
      >
        <a className={buttonVariants({ variant: "outline" })} href={`/api/v1/platform/usage/export?${filter}`}>
          Export CSV
        </a>
      </PageHeader>
      <div className="flex flex-wrap items-end gap-3">
        <div className="space-y-1.5">
          <Label htmlFor="usage-search">Organisation</Label>
          <Input
            id="usage-search"
            value={q}
            onChange={(event) => {
              setQ(event.target.value);
              setPage(0);
            }}
          />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="usage-alert">Alert</Label>
          <select
            id="usage-alert"
            className="h-9 rounded-md border bg-background px-2 text-sm"
            value={alert}
            onChange={(event) => {
              setAlert(event.target.value);
              setPage(0);
            }}
          >
            <option value="">Any</option>
            <option value="NEAR">Near a limit</option>
            <option value="REACHED">Limit reached</option>
          </select>
        </div>
      </div>
      {!data ? (
        <QueryState error={error} />
      ) : data.items.length === 0 ? (
        <p className="text-sm text-muted-foreground">No organisations match.</p>
      ) : (
        <>
          <p role="status" className="text-sm">
            {data.total} {data.total === 1 ? "organisation" : "organisations"}, page {page + 1}
          </p>
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Organisation</TableHead>
                  <TableHead>Users</TableHead>
                  <TableHead>Facilities</TableHead>
                  <TableHead>Storage</TableHead>
                  <TableHead>Inspections this month</TableHead>
                  <TableHead>Attachments</TableHead>
                  <TableHead>Alert</TableHead>
                  <TableHead />
                </TableRow>
              </TableHeader>
              <TableBody>
                {data.items.map((row) => (
                  <TableRow key={row.tenantId}>
                    <TableCell>
                      <Link className="underline" href={`/platform/organisations/${row.tenantId}`}>
                        {row.name}
                      </Link>
                      <div className="text-xs text-muted-foreground">{row.planName}</div>
                    </TableCell>
                    <TableCell>{usageText(row.users, row.maxUsers)}</TableCell>
                    <TableCell>{usageText(row.facilities, row.maxFacilities)}</TableCell>
                    <TableCell>{storageText(row.storageBytes, row.maxStorageMb)}</TableCell>
                    <TableCell>{usageText(row.inspections, row.maxMonthlyInspections)}</TableCell>
                    <TableCell>{usageText(row.attachments, row.maxAttachments)}</TableCell>
                    <TableCell>
                      {LEVEL[row.level]}
                      {row.upgradeTo && (
                        <div className="text-xs text-muted-foreground">Would fit on {row.upgradeTo}</div>
                      )}
                    </TableCell>
                    <TableCell>
                      <Button size="sm" variant="outline" onClick={() => setViewing(row)}>
                        History
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <div className="flex gap-2">
            <Button variant="outline" disabled={page === 0} onClick={() => setPage(page - 1)}>
              Previous
            </Button>
            <Button variant="outline" disabled={(page + 1) * PAGE_SIZE >= data.total} onClick={() => setPage(page + 1)}>
              Next
            </Button>
          </div>
        </>
      )}
      {viewing && <History row={viewing} onClose={() => setViewing(null)} />}
    </div>
  );
}
