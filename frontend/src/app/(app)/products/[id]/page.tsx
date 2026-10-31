"use client";

import { useQuery } from "@tanstack/react-query";
import { useParams } from "next/navigation";
import { useState } from "react";
import { FormError } from "@/components/forms/field";
import { AttachmentPanel } from "@/components/records/attachment-panel";
import {
  Details,
  PageHeader,
  QueryState,
  Section,
  SpecificationTable,
  specificationFields,
  useAction,
  useMaterials,
  useProductCategories,
  useRefresh,
  useUnits,
} from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { productFields } from "@/lib/record-fields";
import { formatQuantity, type FieldDef } from "@/lib/record-form";
import type { ProductDetail } from "@/lib/types";

const REVISION_FIELDS: FieldDef[] = [
  { name: "revision", label: "Revision", required: true },
  { name: "changeNote", label: "What changed" },
  { name: "effectiveFrom", label: "Effective from", type: "date", required: true },
];

export default function ProductPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/products/${id}`;
  const canManage = usePermission("PRODUCT_MANAGE");
  const units = useUnits();
  const categories = useProductCategories();
  const materials = useMaterials();
  const refresh = useRefresh();
  const { run, message } = useAction();
  const [dialog, setDialog] = useState<"edit" | "revision" | "specification" | "material" | null>(null);
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<ProductDetail>(path) });
  if (!data) return <QueryState error={error} />;

  const { product, revisions, specifications } = data;
  const add = (label: string, target: typeof dialog) =>
    canManage && (
      <Button variant="outline" size="sm" onClick={() => setDialog(target)}>
        {label}
      </Button>
    );

  return (
    <div className="space-y-6">
      <PageHeader title={product.name} subtitle={`${product.code} · revision ${product.currentRevision}`}>
        {canManage && (
          <Button variant="outline" onClick={() => setDialog("edit")}>
            Edit
          </Button>
        )}
      </PageHeader>
      <FormError message={message} />
      <Section title="Details">
        <Details
          items={[
            ["Type", product.productType === "FINISHED" ? "Finished" : "Semi-finished"],
            ["Unit", units.label(product.uomId)],
            ["Description", product.description],
          ]}
        />
      </Section>
      <Section title="Specifications" action={add("Add specification", "specification")}>
        <SpecificationTable
          specifications={specifications}
          unitLabel={units.label}
          onRemove={
            canManage ? (specId) => run(() => api.delete(`${path}/specifications/${specId}`)) : undefined
          }
        />
      </Section>
      <Section title="Materials" action={add("Add material", "material")}>
        {data.materials.length === 0 && <p className="text-sm text-muted-foreground">No materials mapped.</p>}
        <ul className="divide-y text-sm">
          {data.materials.map((line) => (
            <li key={line.id} className="flex items-center justify-between gap-3 py-2">
              <span>
                {line.materialCode} – {line.materialName}
              </span>
              <span className="ml-auto text-muted-foreground">
                {formatQuantity(line.quantityPerUnit)} {units.label(line.uomId)}
              </span>
              {canManage && (
                <Button variant="ghost" size="sm" onClick={() => run(() => api.delete(`${path}/materials/${line.id}`))}>
                  Remove
                </Button>
              )}
            </li>
          ))}
        </ul>
      </Section>
      <Section title="Revision history" action={add("New revision", "revision")}>
        <ul className="divide-y text-sm">
          {revisions.map((revision) => (
            <li key={revision.id} className="flex justify-between gap-3 py-2">
              <span className="font-medium">{revision.revision}</span>
              <span className="text-muted-foreground">
                {revision.changeNote} · from {revision.effectiveFrom}
              </span>
            </li>
          ))}
        </ul>
      </Section>
      <AttachmentPanel entityType="PRODUCT" entityId={id} canManage={canManage} />
      {dialog === "edit" && (
        <RecordDialog
          title="Edit product"
          fields={productFields(units.options, categories)}
          record={product}
          onClose={() => setDialog(null)}
          onSubmit={(values) => api.put(path, { ...values, version: product.version }).then(refresh)}
        />
      )}
      {dialog === "revision" && (
        <RecordDialog
          title="New revision"
          fields={REVISION_FIELDS}
          onClose={() => setDialog(null)}
          onSubmit={(values) => api.post(`${path}/revisions`, values).then(refresh)}
        />
      )}
      {dialog === "specification" && (
        <RecordDialog
          title="Add specification"
          fields={specificationFields(units.options)}
          onClose={() => setDialog(null)}
          onSubmit={(values) => api.post(`${path}/specifications`, values).then(refresh)}
        />
      )}
      {dialog === "material" && (
        <RecordDialog
          title="Add material"
          fields={[
            { name: "materialId", label: "Material", type: "select", required: true, options: materials },
            { name: "quantityPerUnit", label: "Quantity per unit", type: "decimal", required: true },
            { name: "uomId", label: "Unit", type: "select", required: true, options: units.options },
          ]}
          onClose={() => setDialog(null)}
          onSubmit={(values) => api.post(`${path}/materials`, values).then(refresh)}
        />
      )}
    </div>
  );
}
