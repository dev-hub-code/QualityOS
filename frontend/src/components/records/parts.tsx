"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useState, type ReactNode } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { api, ApiError, type PageResponse } from "@/lib/api";
import { formatQuantity, type FieldDef, type FieldOption } from "@/lib/record-form";
import type {
  Assignee,
  DefectCategory,
  Category,
  Facility,
  Machine,
  Material,
  Product,
  Specification,
  Supplier,
  Unit,
  WorkCenter,
} from "@/lib/types";

const NEGATIVE = new Set(["REJECTED", "DISCONTINUED", "REJECT", "BLOCKED", "CANCELLED", "VOIDED", "FAIL", "HOLD", "CRITICAL", "OVERDUE", "ON_HOLD", "REVERSED", "REINSPECT"]);
const POSITIVE = new Set(["APPROVED", "RECEIVED", "CLOSED", "NOT_REQUIRED", "PASS", "ACCEPT", "PUBLISHED", "COMPLETED", "MINOR", "RELEASED", "APPLIED", "ACCEPTED", "SHIPPED", "PAID"]);

/** @param label shown instead of the status itself, which still decides the colour */
export function StatusBadge({ status, label }: { status: string; label?: string }) {
  const variant = NEGATIVE.has(status) ? "destructive" : POSITIVE.has(status) ? "default" : "secondary";
  return <Badge variant={variant}>{label ?? status.replaceAll("_", " ").toLowerCase()}</Badge>;
}

export function PageHeader({ title, subtitle, children }: { title: string; subtitle?: ReactNode; children?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-3">
      <div>
        <h1 className="text-2xl font-bold tracking-tight text-[#1b2a55] sm:text-3xl">{title}</h1>
        {subtitle && <div className="mt-1.5 text-sm text-muted-foreground">{subtitle}</div>}
      </div>
      {children && <div className="flex flex-wrap gap-2">{children}</div>}
    </div>
  );
}

export function RecordLink({ href, children }: { href: string; children: ReactNode }) {
  return (
    <Link href={href} className="font-medium text-primary underline-offset-4 hover:underline">
      {children}
    </Link>
  );
}

export function Section({ title, action, children }: { title: string; action?: ReactNode; children: ReactNode }) {
  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between gap-2">
        <CardTitle className="text-base">{title}</CardTitle>
        {action}
      </CardHeader>
      <CardContent>{children}</CardContent>
    </Card>
  );
}

export function Details({ items }: { items: [string, ReactNode][] }) {
  return (
    <dl className="grid gap-x-6 gap-y-3 sm:grid-cols-2 lg:grid-cols-3">
      {items.map(([label, value]) => (
        <div key={label}>
          <dt className="text-sm text-muted-foreground">{label}</dt>
          <dd className="text-sm">{value || "—"}</dd>
        </div>
      ))}
    </dl>
  );
}

export function QueryState({ error }: { error: Error | null }) {
  if (error instanceof ApiError && error.status === 404) {
    return <p className="text-sm text-muted-foreground">This record does not exist.</p>;
  }
  if (error) return <p className="text-sm text-destructive">{error.message}</p>;
  return <p className="text-sm text-muted-foreground">Loading…</p>;
}

export function useRefresh() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries();
}

/** Runs a mutation, refreshes everything shown, and reports any refusal. */
export function useAction() {
  const queryClient = useQueryClient();
  const [message, setMessage] = useState("");
  const mutation = useMutation({
    mutationFn: (action: () => Promise<unknown>) => action(),
    onMutate: () => setMessage(""),
    onSuccess: () => queryClient.invalidateQueries(),
    onError: (error) => setMessage(error.message),
  });
  return { run: mutation.mutate, pending: mutation.isPending, message };
}

/** Lists that rarely change. A change made here refreshes them at once, since every action invalidates all queries. */
const REFERENCE_STALE_TIME = 5 * 60_000;

export function useUnits() {
  const { data = [] } = useQuery({
    queryKey: ["/units"],
    queryFn: () => api.get<Unit[]>("/units"),
    staleTime: REFERENCE_STALE_TIME,
  });
  return {
    options: data.map((unit): FieldOption => ({ value: unit.id, label: `${unit.code} – ${unit.name}` })),
    label: (id: string | null) => data.find((unit) => unit.id === id)?.code ?? "",
  };
}

export function useProductCategories(): FieldOption[] {
  const { data = [] } = useQuery({
    queryKey: ["/product-categories"],
    queryFn: () => api.get<Category[]>("/product-categories"),
    staleTime: REFERENCE_STALE_TIME,
  });
  return data.map((category) => ({ value: category.id, label: category.name }));
}

export function useSupplierCategories(): FieldOption[] {
  const { data = [] } = useQuery({
    queryKey: ["/suppliers/categories"],
    queryFn: () => api.get<Category[]>("/suppliers/categories"),
    staleTime: REFERENCE_STALE_TIME,
  });
  return data.map((category) => ({ value: category.id, label: category.name }));
}

const LOOKUP_SIZE = 100;
/** A picker reads at most this many pages, 5,000 records, which a native select still searches by typing. */
const LOOKUP_PAGES = 50;

/** Every active record, page by page, so that a picker is not cut off at one page. */
export async function lookupAll<T>(path: string, filter = ""): Promise<T[]> {
  const page = (number: number) => api.get<PageResponse<T>>(`${path}?size=${LOOKUP_SIZE}&page=${number}${filter}`);
  const first = await page(0);
  const remaining = Math.min(first.totalPages, LOOKUP_PAGES) - 1;
  if (remaining <= 0) return first.items;
  const rest = await Promise.all(Array.from({ length: remaining }, (_, index) => page(index + 1)));
  return [first, ...rest].flatMap((result) => result.items);
}

function useLookup<T extends { id: string; code: string; name: string }>(
  path: string,
  filter = "",
  enabled = true,
): FieldOption[] {
  const { data } = useQuery({
    queryKey: [path, `lookup${filter}`],
    queryFn: () => lookupAll<T>(path, filter),
    enabled,
  });
  return (data ?? []).map((item) => ({ value: item.id, label: `${item.code} – ${item.name}` }));
}

export const useApprovedSuppliers = () => useLookup<Supplier>("/suppliers", "&status=APPROVED");
/**
 * Active members to hand work to. With a permission, only those who hold it, so nobody is named on work their role
 * cannot do: NCR_ASSIGN for an NCR, CAPA_MANAGE for a CAPA owner, AUDIT_MANAGE for a lead auditor and RECALL_MANAGE
 * for recall contacts. Without one, everyone, as for CAPA action owners and instrument custodians.
 */
export function useAssignees(permission?: "NCR_ASSIGN" | "CAPA_MANAGE" | "AUDIT_MANAGE" | "RECALL_MANAGE") {
  const path = permission ? `/quality/assignees?permission=${permission}` : "/quality/assignees";
  const { data = [] } = useQuery({
    queryKey: [path],
    queryFn: () => api.get<Assignee[]>(path),
  });
  return data.map((person): FieldOption => ({ value: person.id, label: person.fullName }));
}

export function useDefectCategories() {
  const { data = [] } = useQuery({
    queryKey: ["/defect-categories"],
    queryFn: () => api.get<DefectCategory[]>("/defect-categories"),
    staleTime: REFERENCE_STALE_TIME,
  });
  return data.map((category): FieldOption => ({ value: category.id, label: `${category.code} – ${category.name}` }));
}

export const useFacilities = () => useLookup<Facility>("/facilities");
export const useMaterials = () => useLookup<Material>("/materials");
export const useProducts = () => useLookup<Product>("/products");
/** @param enabled false for someone who cannot see work centres, so the page does not ask and get refused */
export const useWorkCenters = (enabled = true) => useLookup<WorkCenter>("/manufacturing/work-centers", "", enabled);
export const useMachines = () => useLookup<Machine>("/manufacturing/machines");

export function SpecificationTable({
  specifications,
  unitLabel,
  onRemove,
}: {
  specifications: Specification[];
  unitLabel: (id: string | null) => string;
  onRemove?: (id: string) => void;
}) {
  if (specifications.length === 0) {
    return <p className="text-sm text-muted-foreground">No specifications yet.</p>;
  }
  return (
    <ul className="divide-y text-sm">
      {specifications.map((spec) => (
        <li key={spec.id} className="flex items-center justify-between gap-3 py-2">
          <span className="font-medium">{spec.name}</span>
          <span className="ml-auto text-muted-foreground">
            {spec.textValue ??
              [spec.minValue, spec.nominalValue, spec.maxValue]
                .map((value) => (value === null ? "–" : formatQuantity(value)))
                .join(" / ")}{" "}
            {unitLabel(spec.uomId)}
          </span>
          {onRemove && (
            <Button variant="ghost" size="sm" onClick={() => onRemove(spec.id)}>
              Remove
            </Button>
          )}
        </li>
      ))}
    </ul>
  );
}

export const specificationFields = (units: FieldOption[]): FieldDef[] => [
  { name: "name", label: "Characteristic", required: true },
  { name: "minValue", label: "Minimum", type: "decimal" },
  { name: "nominalValue", label: "Nominal", type: "decimal" },
  { name: "maxValue", label: "Maximum", type: "decimal" },
  { name: "textValue", label: "Text requirement" },
  { name: "uomId", label: "Unit", type: "select", options: units },
];
