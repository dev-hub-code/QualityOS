"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useState, type ReactNode } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { api, ApiError, type PageResponse } from "@/lib/api";
import { formatQuantity, type FieldDef, type FieldOption } from "@/lib/record-form";
import type { Assignee, DefectCategory, Category, Facility, Material, Product, Specification, Supplier, Unit } from "@/lib/types";

const NEGATIVE = new Set(["REJECTED", "REJECT", "BLOCKED", "CANCELLED", "VOIDED", "FAIL", "HOLD", "CRITICAL", "OVERDUE", "ON_HOLD"]);
const POSITIVE = new Set(["APPROVED", "RECEIVED", "CLOSED", "NOT_REQUIRED", "PASS", "ACCEPT", "PUBLISHED", "COMPLETED", "MINOR", "RELEASED"]);

export function StatusBadge({ status }: { status: string }) {
  const variant = NEGATIVE.has(status) ? "destructive" : POSITIVE.has(status) ? "default" : "secondary";
  return <Badge variant={variant}>{status.replaceAll("_", " ").toLowerCase()}</Badge>;
}

export function PageHeader({ title, subtitle, children }: { title: string; subtitle?: ReactNode; children?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-3">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
        {subtitle && <div className="mt-1 text-sm text-muted-foreground">{subtitle}</div>}
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

export function useUnits() {
  const { data = [] } = useQuery({ queryKey: ["/units"], queryFn: () => api.get<Unit[]>("/units") });
  return {
    options: data.map((unit): FieldOption => ({ value: unit.id, label: `${unit.code} – ${unit.name}` })),
    label: (id: string | null) => data.find((unit) => unit.id === id)?.code ?? "",
  };
}

export function useProductCategories(): FieldOption[] {
  const { data = [] } = useQuery({
    queryKey: ["/product-categories"],
    queryFn: () => api.get<Category[]>("/product-categories"),
  });
  return data.map((category) => ({ value: category.id, label: category.name }));
}

export function useSupplierCategories(): FieldOption[] {
  const { data = [] } = useQuery({
    queryKey: ["/suppliers/categories"],
    queryFn: () => api.get<Category[]>("/suppliers/categories"),
  });
  return data.map((category) => ({ value: category.id, label: category.name }));
}

const LOOKUP_SIZE = 100;

function useLookup<T extends { id: string; code: string; name: string }>(path: string, filter = ""): FieldOption[] {
  const query = `size=${LOOKUP_SIZE}${filter}`;
  const { data } = useQuery({
    queryKey: [path, query],
    queryFn: () => api.get<PageResponse<T>>(`${path}?${query}`),
  });
  return (data?.items ?? []).map((item) => ({ value: item.id, label: `${item.code} – ${item.name}` }));
}

export const useApprovedSuppliers = () => useLookup<Supplier>("/suppliers", "&status=APPROVED");
export function useAssignees() {
  const { data = [] } = useQuery({
    queryKey: ["/quality/assignees"],
    queryFn: () => api.get<Assignee[]>("/quality/assignees"),
  });
  return data.map((person): FieldOption => ({ value: person.id, label: person.fullName }));
}

export function useDefectCategories() {
  const { data = [] } = useQuery({
    queryKey: ["/defect-categories"],
    queryFn: () => api.get<DefectCategory[]>("/defect-categories"),
  });
  return data.map((category): FieldOption => ({ value: category.id, label: `${category.code} – ${category.name}` }));
}

export const useFacilities = () => useLookup<Facility>("/facilities");
export const useMaterials = () => useLookup<Material>("/materials");
export const useProducts = () => useLookup<Product>("/products");

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
