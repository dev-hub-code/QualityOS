"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { FormError } from "@/components/forms/field";
import { PageHeader, useAction, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import type { FieldDef } from "@/lib/record-form";
import type { Category, DefectCategory } from "@/lib/types";

const NAME_FIELDS: FieldDef[] = [{ name: "name", label: "Name", required: true }];
const DEFECT_FIELDS: FieldDef[] = [{ name: "code", label: "Code", required: true }, ...NAME_FIELDS];

interface SectionProps {
  title: string;
  /** What a new one is called, such as "supplier category". */
  noun: string;
  path: string;
  fields: FieldDef[];
  canView: boolean;
  canManage: boolean;
  /** Defect categories stay on closed NCRs, so they are archived, not renamed or deleted. */
  archives?: boolean;
}

function CategorySection({ title, noun, path, fields, canView, canManage, archives }: SectionProps) {
  const refresh = useRefresh();
  const { run, message } = useAction();
  const [adding, setAdding] = useState(false);
  const [renaming, setRenaming] = useState<Category | null>(null);
  const { data = [] } = useQuery({
    queryKey: [path],
    queryFn: () => api.get<(Category & Partial<DefectCategory>)[]>(path),
    enabled: canView,
  });
  if (!canView) return null;
  const headingId = `${noun.replace(" ", "-")}-heading`;

  return (
    <section aria-labelledby={headingId} className="space-y-3">
      <div className="flex items-center justify-between gap-3">
        <h2 id={headingId} className="font-medium">
          {title}
        </h2>
        {canManage && (
          <Button size="sm" variant="outline" onClick={() => setAdding(true)}>
            Add {noun}
          </Button>
        )}
      </div>
      <FormError message={message} />
      {data.length === 0 ? (
        <p className="text-sm text-muted-foreground">No categories yet.</p>
      ) : (
        <ul className="divide-y rounded-lg border text-sm">
          {data.map((category) => (
            <li key={category.id} className="flex items-center justify-between gap-3 p-3">
              <span>{category.code ? `${category.code} – ${category.name}` : category.name}</span>
              {canManage && (
                <span className="flex gap-1">
                  {!archives && (
                    <Button variant="ghost" size="sm" onClick={() => setRenaming(category)}>
                      Rename
                    </Button>
                  )}
                  <Button variant="ghost" size="sm" onClick={() => run(() => api.delete(`${path}/${category.id}`))}>
                    {archives ? "Archive" : "Delete"}
                  </Button>
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
      {adding && (
        <RecordDialog
          title={`Add ${noun}`}
          fields={fields}
          onClose={() => setAdding(false)}
          onSubmit={(values) => api.post(path, values).then(refresh)}
        />
      )}
      {renaming && (
        <RecordDialog
          title={`Rename ${noun}`}
          fields={fields}
          record={renaming}
          onClose={() => setRenaming(null)}
          onSubmit={(values) => api.put(`${path}/${renaming.id}`, values).then(refresh)}
        />
      )}
    </section>
  );
}

export default function CategoriesPage() {
  const canViewSuppliers = usePermission("SUPPLIER_VIEW");
  const canManageSuppliers = usePermission("SUPPLIER_UPDATE");
  const canViewProducts = usePermission("PRODUCT_VIEW");
  const canManageProducts = usePermission("PRODUCT_MANAGE");
  const canViewDefects = usePermission("NCR_VIEW");
  const canManageDefects = usePermission("NCR_ASSIGN");
  return (
    <div className="max-w-2xl space-y-8">
      <PageHeader title="Categories" subtitle="The labels used on suppliers, materials, products and NCRs." />
      <CategorySection
        title="Supplier categories"
        noun="supplier category"
        path="/suppliers/categories"
        fields={NAME_FIELDS}
        canView={canViewSuppliers}
        canManage={canManageSuppliers}
      />
      <CategorySection
        title="Product categories"
        noun="product category"
        path="/product-categories"
        fields={NAME_FIELDS}
        canView={canViewProducts}
        canManage={canManageProducts}
      />
      <CategorySection
        title="Defect categories"
        noun="defect category"
        path="/defect-categories"
        fields={DEFECT_FIELDS}
        canView={canViewDefects}
        canManage={canManageDefects}
        archives
      />
    </div>
  );
}
