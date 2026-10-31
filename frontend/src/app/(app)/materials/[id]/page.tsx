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
  useProductCategories,
  useRefresh,
  useUnits,
} from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { materialFields } from "@/lib/record-fields";
import type { MaterialDetail } from "@/lib/types";

export default function MaterialPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/materials/${id}`;
  const canManage = usePermission("PRODUCT_MANAGE");
  const units = useUnits();
  const categories = useProductCategories();
  const refresh = useRefresh();
  const { run, message } = useAction();
  const [dialog, setDialog] = useState<"edit" | "specification" | null>(null);
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<MaterialDetail>(path) });
  if (!data) return <QueryState error={error} />;

  const { material, specifications } = data;
  return (
    <div className="space-y-6">
      <PageHeader title={material.name} subtitle={material.code}>
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
            ["Grade", material.grade],
            ["Unit", units.label(material.uomId)],
            ["Inspection on receipt", material.inspectionRequired ? "Required" : "Not required"],
            ["Description", material.description],
          ]}
        />
      </Section>
      <Section
        title="Specifications"
        action={
          canManage && (
            <Button variant="outline" size="sm" onClick={() => setDialog("specification")}>
              Add specification
            </Button>
          )
        }
      >
        <SpecificationTable
          specifications={specifications}
          unitLabel={units.label}
          onRemove={
            canManage ? (specId) => run(() => api.delete(`${path}/specifications/${specId}`)) : undefined
          }
        />
      </Section>
      <AttachmentPanel entityType="MATERIAL" entityId={id} canManage={canManage} />
      {dialog === "edit" && (
        <RecordDialog
          title="Edit material"
          fields={materialFields(units.options, categories)}
          record={material}
          onClose={() => setDialog(null)}
          onSubmit={(values) => api.put(path, { ...values, version: material.version }).then(refresh)}
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
    </div>
  );
}
