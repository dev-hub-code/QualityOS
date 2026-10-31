"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useState, type ReactNode } from "react";
import { QueryState } from "@/components/records/parts";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { api } from "@/lib/api";
import type { OrganisationList } from "@/lib/types";

const PAGE_SIZE = 25;
const DAY = 86_400_000;

export const daysLeft = (endsAt: string) => Math.max(0, Math.ceil((new Date(endsAt).getTime() - Date.now()) / DAY));

export type SubscriptionRow = OrganisationList["items"][number];

interface SubscriptionTableProps {
  /** Fixes the state, for a page that only ever shows one. */
  state?: string;
  stateOptions?: [string, string][];
  columns: [string, (row: SubscriptionRow) => ReactNode][];
  empty: string;
}

export function SubscriptionTable({ state, stateOptions, columns, empty }: SubscriptionTableProps) {
  const [q, setQ] = useState("");
  const [chosen, setChosen] = useState("");
  const [page, setPage] = useState(0);
  const filter = new URLSearchParams({ size: String(PAGE_SIZE), page: String(page), sort: "ENDING" });
  if (q) filter.set("q", q);
  if (state ?? chosen) filter.set("state", (state ?? chosen) as string);
  const path = `/platform/organisations?${filter}`;
  const { data, error } = useQuery({
    queryKey: [path],
    queryFn: () => api.get<OrganisationList>(path),
    placeholderData: keepPreviousData,
  });
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end gap-3">
        <div className="space-y-1.5">
          <Label htmlFor="subscription-search">Search</Label>
          <Input
            id="subscription-search"
            placeholder="Name, email or GSTIN"
            value={q}
            onChange={(event) => {
              setQ(event.target.value);
              setPage(0);
            }}
          />
        </div>
        {stateOptions && (
          <div className="space-y-1.5">
            <Label htmlFor="subscription-state">Status</Label>
            <select
              id="subscription-state"
              className="h-9 rounded-md border bg-background px-2 text-sm"
              value={chosen}
              onChange={(event) => {
                setChosen(event.target.value);
                setPage(0);
              }}
            >
              <option value="">Any</option>
              {stateOptions.map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </div>
        )}
      </div>
      {!data ? (
        <QueryState error={error} />
      ) : data.items.length === 0 ? (
        <p className="text-sm text-muted-foreground">{empty}</p>
      ) : (
        <>
          <p role="status" className="text-sm">
            {data.total} found, page {page + 1}
          </p>
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Organisation</TableHead>
                  {columns.map(([heading]) => (
                    <TableHead key={heading}>{heading}</TableHead>
                  ))}
                </TableRow>
              </TableHeader>
              <TableBody>
                {data.items.map((row) => (
                  <TableRow key={row.tenantId}>
                    <TableCell>
                      <Link className="underline" href={`/platform/organisations/${row.tenantId}`}>
                        {row.organizationName}
                      </Link>
                    </TableCell>
                    {columns.map(([heading, value]) => (
                      <TableCell key={heading}>{value(row)}</TableCell>
                    ))}
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
    </div>
  );
}

