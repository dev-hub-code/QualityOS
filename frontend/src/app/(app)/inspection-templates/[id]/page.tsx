"use client";

import { useQuery } from "@tanstack/react-query";
import { useParams } from "next/navigation";
import { useState } from "react";
import { FormError } from "@/components/forms/field";
import { PageHeader, QueryState, Section, StatusBadge, useAction, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { TemplateDraftForm } from "@/components/records/template-draft-form";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { requirement } from "@/lib/inspection";
import type { FieldDef } from "@/lib/record-form";
import type { TemplateDetail, TemplateVersion } from "@/lib/types";

const PUBLISH_FIELDS: FieldDef[] = [
  { name: "effectiveFrom", label: "Effective from", type: "date", required: true },
];

function sampling(version: TemplateVersion) {
  if (version.samplingType === "FULL") return "Every unit";
  return version.samplingType === "FIXED" ? `${version.sampleSize} samples` : `${version.samplePercent}% of the lot`;
}

function PublishedVersion({ version }: { version: TemplateVersion }) {
  return (
    <Section title={`Version ${version.versionNumber}`} action={<StatusBadge status={version.status} />}>
      <p className="mb-3 text-sm text-muted-foreground">
        {sampling(version)}, up to {version.acceptanceNumber} failures allowed · from {version.effectiveFrom}
        {version.effectiveTo && ` to ${version.effectiveTo}`}
      </p>
      <ul className="divide-y text-sm">
        {version.parameters.map((parameter) => (
          <li key={parameter.code} className="flex justify-between gap-3 py-2">
            <span>
              {parameter.code} – {parameter.name}
            </span>
            <span className="text-muted-foreground">
              {requirement(parameter)} · {parameter.criticality.toLowerCase()}
            </span>
          </li>
        ))}
      </ul>
    </Section>
  );
}

export default function InspectionTemplatePage() {
  const { id } = useParams<{ id: string }>();
  const path = `/inspection-templates/${id}`;
  const canManage = usePermission("TEMPLATE_MANAGE");
  const canPublish = usePermission("TEMPLATE_PUBLISH");
  const refresh = useRefresh();
  const { run, pending, message } = useAction();
  const [publishing, setPublishing] = useState(false);
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<TemplateDetail>(path) });
  if (!data) return <QueryState error={error} />;

  const { template, versions } = data;
  const draft = versions.find((version) => version.status === "DRAFT");

  return (
    <div className="space-y-6">
      <PageHeader title={template.name} subtitle={`${template.code} · ${template.inspectionType}`}>
        {canManage && !draft && (
          <Button disabled={pending} onClick={() => run(() => api.post(`${path}/versions`))}>
            New version
          </Button>
        )}
        {canPublish && draft && (
          <Button onClick={() => setPublishing(true)} disabled={draft.parameters.length === 0}>
            Publish
          </Button>
        )}
      </PageHeader>
      <FormError message={message} />
      {draft && canManage && <TemplateDraftForm key={draft.id} templateId={id} draft={draft} />}
      {versions
        .filter((version) => version.status !== "DRAFT")
        .map((version) => (
          <PublishedVersion key={version.id} version={version} />
        ))}
      {publishing && (
        <RecordDialog
          title={`Publish version ${draft?.versionNumber}`}
          fields={PUBLISH_FIELDS}
          record={{ effectiveFrom: new Date().toISOString().slice(0, 10) }}
          onClose={() => setPublishing(false)}
          onSubmit={(values) => api.post(`${path}/publish`, values).then(refresh)}
        />
      )}
    </div>
  );
}
