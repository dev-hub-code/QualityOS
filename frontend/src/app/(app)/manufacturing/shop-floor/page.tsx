"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useState } from "react";
import { PageHeader, StatusBadge, useFacilities, useUnits, useWorkCenters } from "@/components/records/parts";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { api } from "@/lib/api";
import { PRIORITY_LABELS } from "@/lib/manufacturing";
import { formatQuantity } from "@/lib/record-form";
import type { PageResponse } from "@/lib/api";
import type { OperationSummary } from "@/lib/types";

const PAGE_SIZE = 20;
const select = "h-11 rounded-xl border bg-background px-3 text-sm";

/** The floor: open operations of orders in production, running ones first. Built for a phone or a tablet at the line. */
export default function ShopFloorPage() {
  const [mine, setMine] = useState(false);
  const [search, setSearch] = useState("");
  const [facilityId, setFacilityId] = useState("");
  const [workCenterId, setWorkCenterId] = useState("");
  const [page, setPage] = useState(0);
  const facilities = useFacilities();
  const workCenters = useWorkCenters();
  const units = useUnits();
  const params = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE), mine: String(mine) });
  if (search) params.set("q", search);
  if (facilityId) params.set("facilityId", facilityId);
  if (workCenterId) params.set("workCenterId", workCenterId);
  const { data, isPending, error } = useQuery({
    queryKey: ["/manufacturing/operations", params.toString()],
    queryFn: () => api.get<PageResponse<OperationSummary>>(`/manufacturing/operations?${params}`),
    placeholderData: keepPreviousData,
    refetchInterval: 30_000,
  });
  const reset = <T,>(set: (value: T) => void) => (value: T) => {
    set(value);
    setPage(0);
  };

  return (
    <div className="space-y-6">
      <PageHeader title="Shop floor" subtitle="Operations of work orders in production" />
      <div className="flex flex-wrap gap-2">
        <div className="flex rounded-xl border p-1" role="group" aria-label="Whose operations">
          <Button size="sm" variant={mine ? "ghost" : "default"} onClick={() => reset(setMine)(false)}>
            All
          </Button>
          <Button size="sm" variant={mine ? "default" : "ghost"} onClick={() => reset(setMine)(true)}>
            Mine
          </Button>
        </div>
        <Input
          aria-label="Search by work order or product"
          placeholder="Work order or product"
          className="h-11 w-full sm:w-64"
          value={search}
          onChange={(event) => reset(setSearch)(event.target.value)}
        />
        <select aria-label="Facility" className={select} value={facilityId} onChange={(event) => reset(setFacilityId)(event.target.value)}>
          <option value="">All facilities</option>
          {facilities.map((facility) => (
            <option key={facility.value} value={facility.value}>
              {facility.label}
            </option>
          ))}
        </select>
        <select aria-label="Work centre" className={select} value={workCenterId} onChange={(event) => reset(setWorkCenterId)(event.target.value)}>
          <option value="">All work centres</option>
          {workCenters.map((centre) => (
            <option key={centre.value} value={centre.value}>
              {centre.label}
            </option>
          ))}
        </select>
      </div>

      {error && <p className="text-sm text-destructive">{error.message}</p>}
      {isPending && <p className="text-sm text-muted-foreground">Loading…</p>}
      {data && data.items.length === 0 && (
        <p className="rounded-xl border border-dashed p-6 text-center text-sm text-muted-foreground">
          {mine ? "Nothing is assigned to you right now." : "No operations are waiting on the floor."}
        </p>
      )}
      <ul className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
        {data?.items.map((operation) => (
          <li key={operation.id}>
            <Link
              href={`/manufacturing/shop-floor/${operation.id}`}
              className="block h-full rounded-2xl border bg-card p-4 shadow-sm transition hover:border-primary focus-visible:outline-2 focus-visible:outline-primary"
            >
              <div className="flex items-start justify-between gap-2">
                <div>
                  <p className="text-sm text-muted-foreground">
                    {operation.workOrderNumber} · {operation.workCenterCode}
                    {operation.priority !== "NORMAL" && ` · ${PRIORITY_LABELS[operation.priority]}`}
                  </p>
                  <p className="text-lg font-semibold">
                    {operation.sequence}. {operation.name}
                  </p>
                  <p className="text-sm">
                    {operation.productCode} – {operation.productName}
                  </p>
                </div>
                <StatusBadge status={operation.status} />
              </div>
              <dl className="mt-3 grid grid-cols-3 gap-2 text-sm">
                <div>
                  <dt className="text-muted-foreground">Waiting</dt>
                  <dd className="font-medium tabular-nums">
                    {formatQuantity(operation.waiting)} {units.label(operation.uomId)}
                  </dd>
                </div>
                <div>
                  <dt className="text-muted-foreground">Good</dt>
                  <dd className="font-medium tabular-nums">{formatQuantity(operation.goodQuantity)}</dd>
                </div>
                <div>
                  <dt className="text-muted-foreground">Due</dt>
                  <dd className="font-medium">{operation.plannedEnd}</dd>
                </div>
              </dl>
              <p className="mt-2 text-sm text-muted-foreground">
                {operation.operatorName ?? "Not assigned"}
                {operation.machineCode && ` · ${operation.machineCode}`}
                {operation.workOrderStatus === "ON_HOLD" && " · order on hold"}
              </p>
              {operation.statusReason && <p className="mt-1 text-sm text-amber-700">{operation.statusReason}</p>}
            </Link>
          </li>
        ))}
      </ul>
      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-between text-sm">
          <Button variant="outline" disabled={page === 0} onClick={() => setPage(page - 1)}>
            Previous
          </Button>
          <span className="text-muted-foreground">
            Page {page + 1} of {data.totalPages}
          </span>
          <Button variant="outline" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>
            Next
          </Button>
        </div>
      )}
    </div>
  );
}
