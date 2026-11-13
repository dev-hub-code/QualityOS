"use client";

import type { ColumnDef } from "@tanstack/react-table";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { DataTable } from "@/components/records/data-table";
import { PageHeader, RecordLink, StatusBadge, useProducts } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { DEFINITIONS, type DefinitionKind } from "@/lib/manufacturing";
import type { FieldDef } from "@/lib/record-form";
import type { Revision } from "@/lib/types";

const SUBTITLES: Record<DefinitionKind, string> = {
  BOM: "What goes into a discrete product, level by level.",
  RECIPE: "What goes into a batch of a process product, with the conditions it is made under.",
  ROUTING: "The operations, in order, that make a product.",
};

function columns(kind: DefinitionKind): ColumnDef<Revision>[] {
  return [
    {
      header: "Product",
      cell: ({ row }) => (
        <RecordLink href={`${DEFINITIONS[kind].path}/${row.original.id}`}>
          {row.original.productCode} – {row.original.productName}
        </RecordLink>
      ),
    },
    { header: "Revision", accessorKey: "revision" },
    { header: "Status", cell: ({ row }) => <StatusBadge status={row.original.status} /> },
    {
      header: "In effect",
      cell: ({ row }) =>
        row.original.effectiveFrom
          ? `${row.original.effectiveFrom} to ${row.original.effectiveTo ?? "open"}${row.original.inEffect ? " (now)" : ""}`
          : "–",
    },
  ];
}

export function RevisionList({ kind }: { kind: DefinitionKind }) {
  const definition = DEFINITIONS[kind];
  const canManage = usePermission(definition.manage);
  const products = useProducts();
  const router = useRouter();
  const [adding, setAdding] = useState(false);
  const fields: FieldDef[] = [
    { name: "productId", label: "Product", type: "select", required: true, options: products },
    { name: "changeNote", label: "What this revision is for", type: "textarea" },
  ];
  if (kind !== "ROUTING") {
    fields.push({
      name: "baseQuantity",
      label: kind === "RECIPE" ? "Batch size, in the product's unit" : "Quantity the lines make, in the product's unit (default 1)",
      type: "decimal",
      required: kind === "RECIPE",
    });
  }
  if (kind === "BOM") {
    fields.push({ name: "fromProductMaterials", label: "Start from the product's material list", type: "checkbox" });
  }
  return (
    <div className="space-y-6">
      <PageHeader title={definition.title} subtitle={SUBTITLES[kind]}>
        {canManage && <Button onClick={() => setAdding(true)}>New {definition.one}</Button>}
      </PageHeader>
      <DataTable
        path={`${definition.path}`}
        columns={columns(kind)}
        searchLabel="Search by product"
        emptyMessage={`No ${definition.title.toLowerCase()} yet.`}
      />
      {adding && (
        <RecordDialog
          title={`New ${definition.one}`}
          fields={fields}
          onClose={() => setAdding(false)}
          onSubmit={async (values) => {
            const created = await api.post<{ revision: Revision }>(definition.path, values);
            router.push(`${definition.path}/${created.revision.id}`);
          }}
        />
      )}
    </div>
  );
}
