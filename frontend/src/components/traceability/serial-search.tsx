"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useRouter, useSearchParams } from "next/navigation";
import { useState, type FormEvent } from "react";
import { QueryState, RecordLink, StatusBadge, useFacilities } from "@/components/records/parts";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { api, type PageResponse } from "@/lib/api";
import type { SerialSearchResult } from "@/lib/types";

const PATH = "/traceability/serials";
const SELECT = "h-9 w-full rounded-md border bg-background px-2 text-sm";
const FIELDS = [
  ["", "All fields"],
  ["SERIAL", "Serial number"],
  ["PRODUCT", "Product"],
  ["BATCH", "Batch number"],
  ["CUSTOMER", "Customer"],
  ["DISPATCH", "Dispatch number"],
];
const SORTS = [
  ["serialNumber", "Serial number"],
  ["serialNumber,desc", "Serial number, last first"],
  ["manufacturedAt,desc", "Newest made"],
  ["dispatchedOn,desc", "Latest dispatched"],
  ["customerName", "Customer"],
];
const FILTERS = ["q", "field", "facilityId", "madeFrom", "madeTo", "dispatchedFrom", "dispatchedTo"];

/** Everything lives in the URL, so that a result can be opened and returned to, or the search shared. */
export function SerialSearch() {
  const router = useRouter();
  const params = useSearchParams();
  const facilities = useFacilities();
  const [form, setForm] = useState(() => Object.fromEntries(FILTERS.map((name) => [name, params.get(name) ?? ""])));
  const query = params.toString();
  const searched = FILTERS.some((name) => params.get(name));
  const { data, error, isFetching } = useQuery({
    queryKey: [PATH, query],
    queryFn: () => api.get<PageResponse<SerialSearchResult>>(`${PATH}?${query}`),
    enabled: searched,
    placeholderData: keepPreviousData,
    retry: false,
  });

  const go = (changes: Record<string, string>) => {
    const next = new URLSearchParams(params);
    Object.entries(changes).forEach(([name, value]) => (value ? next.set(name, value) : next.delete(name)));
    router.replace(`/traceability/serials?${next}`);
  };
  const submit = (event: FormEvent) => {
    event.preventDefault();
    go({ ...form, q: form.q.trim(), page: "" });
  };
  const field = (name: string, label: string, type = "date") => (
    <div className="space-y-1.5">
      <Label htmlFor={`serial-${name}`}>{label}</Label>
      <Input
        id={`serial-${name}`}
        type={type}
        value={form[name]}
        onChange={(event) => setForm({ ...form, [name]: event.target.value })}
      />
    </div>
  );
  const page = Number(params.get("page") ?? 0);

  return (
    <div className="space-y-6">
      <form onSubmit={submit} className="space-y-3">
        <div className="grid gap-3 sm:grid-cols-[2fr_1fr]">
          {field("q", "Search", "text")}
          <div className="space-y-1.5">
            <Label htmlFor="serial-field">Search in</Label>
            <select
              id="serial-field"
              className={SELECT}
              value={form.field}
              onChange={(event) => setForm({ ...form, field: event.target.value })}
            >
              {FIELDS.map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </div>
        </div>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
          <div className="space-y-1.5">
            <Label htmlFor="serial-facility">Facility</Label>
            <select
              id="serial-facility"
              className={SELECT}
              value={form.facilityId}
              onChange={(event) => setForm({ ...form, facilityId: event.target.value })}
            >
              <option value="">All facilities</option>
              {facilities.map((facility) => (
                <option key={facility.value} value={facility.value}>
                  {facility.label}
                </option>
              ))}
            </select>
          </div>
          {field("madeFrom", "Made from")}
          {field("madeTo", "Made to")}
          {field("dispatchedFrom", "Dispatched from")}
          {field("dispatchedTo", "Dispatched to")}
        </div>
        <Button type="submit">Search</Button>
      </form>

      {!searched && (
        <p className="text-sm text-muted-foreground">Enter a serial number or part of one, or choose a facility or dates.</p>
      )}
      {searched && isFetching && !data && <p role="status" className="text-sm text-muted-foreground">Searching…</p>}
      {searched && error && <QueryState error={error} />}
      {searched && data?.totalItems === 0 && <p className="text-sm text-muted-foreground">No serial numbers match.</p>}

      {searched && data && data.totalItems > 0 && (
        <>
          <div className="flex flex-wrap items-center justify-between gap-2">
            <p className="text-sm text-muted-foreground">{data.totalItems} found</p>
            <div className="flex items-center gap-2">
              <Label htmlFor="serial-sort">Sort by</Label>
              <select
                id="serial-sort"
                className="h-9 rounded-md border bg-background px-2 text-sm"
                value={params.get("sort") ?? "serialNumber"}
                onChange={(event) => go({ sort: event.target.value, page: "" })}
              >
                {SORTS.map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>
            </div>
          </div>
          <div className="overflow-x-auto">
            <Table>
              <TableHeader>
                <TableRow>
                  {["Serial number", "Product", "Batch", "Made", "Inspection", "Quality", "Dispatch", "Customer", "Dispatched"].map(
                    (heading) => (
                      <TableHead key={heading}>{heading}</TableHead>
                    ),
                  )}
                </TableRow>
              </TableHeader>
              <TableBody>
                {data.items.map((unit) => (
                  <TableRow key={unit.serialNumber}>
                    <TableCell>
                      <RecordLink
                        href={`/traceability/serials/${encodeURIComponent(unit.serialNumber)}?from=${encodeURIComponent(query)}`}
                      >
                        {unit.serialNumber}
                      </RecordLink>
                    </TableCell>
                    <TableCell>
                      {unit.productCode} – {unit.productName}
                    </TableCell>
                    <TableCell>{unit.batchNumber}</TableCell>
                    <TableCell>{unit.manufacturedAt?.slice(0, 10)}</TableCell>
                    <TableCell>{unit.inspectionStatus && <StatusBadge status={unit.inspectionStatus} />}</TableCell>
                    <TableCell>
                      <StatusBadge status={unit.qualityStatus} />
                    </TableCell>
                    <TableCell>
                      <StatusBadge status={unit.dispatchStatus} />
                    </TableCell>
                    <TableCell>{unit.customerName}</TableCell>
                    <TableCell>{unit.dispatchedOn}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          {data.totalPages > 1 && (
            <div className="flex items-center gap-2">
              <Button variant="outline" size="sm" disabled={page === 0} onClick={() => go({ page: String(page - 1) })}>
                Previous
              </Button>
              <span className="text-sm">
                Page {page + 1} of {data.totalPages}
              </span>
              <Button
                variant="outline"
                size="sm"
                disabled={page + 1 >= data.totalPages}
                onClick={() => go({ page: String(page + 1) })}
              >
                Next
              </Button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
