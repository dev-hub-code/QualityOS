"use client";

import { useQuery } from "@tanstack/react-query";
import { useParams, useRouter } from "next/navigation";
import { useState } from "react";
import { AttachmentPanel } from "@/components/records/attachment-panel";
import {
  Details,
  PageHeader,
  QueryState,
  RecordLink,
  Section,
  StatusBadge,
  useAssignees,
  useDefectCategories,
  useRefresh,
} from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { StatusHistory } from "@/components/records/status-history";
import { Button } from "@/components/ui/button";
import { useMe, usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { formatQuantity, type FieldDef } from "@/lib/record-form";
import type { Capa, Ncr } from "@/lib/types";

interface Step {
  label: string;
  action: string;
  fields: FieldDef[];
  outline?: boolean;
}

const note = (label: string, required = true): FieldDef => ({ name: "note", label, type: "textarea", required });

export default function NcrPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/ncrs/${id}`;
  const router = useRouter();
  const refresh = useRefresh();
  const { data: me } = useMe();
  const canCreate = usePermission("NCR_CREATE");
  const canAssign = usePermission("NCR_ASSIGN");
  const canClose = usePermission("NCR_CLOSE");
  const canRaiseCapa = usePermission("CAPA_MANAGE");
  const assignees = useAssignees();
  const categories = useDefectCategories();
  const [step, setStep] = useState<Step | null>(null);
  const [raisingCapa, setRaisingCapa] = useState(false);
  const { data: ncr, error } = useQuery({ queryKey: [path], queryFn: () => api.get<Ncr>(path) });
  if (!ncr) return <QueryState error={error} />;

  const { status } = ncr;
  const finished = ["CLOSED", "REJECTED", "CANCELLED"].includes(status);
  const assign: Step = {
    label: ncr.assignedTo ? "Reassign" : "Assign",
    action: "assign",
    fields: [{ name: "assigneeId", label: "Assign to", type: "select", required: true, options: assignees }],
    outline: Boolean(ncr.assignedTo),
  };
  const steps: Step[] = [];
  if (canAssign && !finished && status !== "PENDING_VERIFICATION") steps.push(assign);
  if (canAssign && status === "UNDER_INVESTIGATION") {
    steps.push({
      label: "Record containment",
      action: "containment",
      fields: [{ name: "containmentAction", label: "Containment action", type: "textarea", required: true }],
    });
  }
  if (canAssign && status === "CONTAINMENT") {
    steps.push({
      label: "Record root cause",
      action: "investigation",
      fields: [
        { name: "rootCause", label: "Root cause", type: "textarea", required: true },
        { name: "defectCategoryId", label: "Defect category", type: "select", options: categories },
      ],
    });
  }
  if (canAssign && status === "ACTION_REQUIRED") {
    steps.push({ label: "Request verification", action: "request-verification", fields: [note("Note", false)] });
  }
  const independent = me?.user.id !== ncr.verificationRequestedBy;
  if (canClose && status === "PENDING_VERIFICATION" && independent) {
    steps.push({ label: "Close NCR", action: "close", fields: [note("Closure note")] });
    steps.push({ label: "Send back", action: "send-back", fields: [note("What is missing")], outline: true });
  }
  if (canClose && (status === "OPEN" || status === "UNDER_INVESTIGATION")) {
    steps.push({ label: "Reject", action: "reject", fields: [note("Why this is not a non-conformance")], outline: true });
  }
  if (canAssign && status === "OPEN" && ncr.source === "MANUAL") {
    steps.push({ label: "Cancel", action: "cancel", fields: [note("Reason")], outline: true });
  }

  const capaFields: FieldDef[] = [
    { name: "title", label: "Title", required: true },
    { name: "problemStatement", label: "Problem statement", type: "textarea", required: true },
    { name: "ownerId", label: "Owner", type: "select", required: true, options: assignees },
    { name: "targetDate", label: "Target date", type: "date", required: true },
  ];

  return (
    <div className="space-y-6">
      <PageHeader
        title={ncr.ncrNumber}
        subtitle={
          <span className="flex flex-wrap items-center gap-2">
            <StatusBadge status={status} />
            <StatusBadge status={ncr.severity} />
            {ncr.title}
          </span>
        }
      >
        {canRaiseCapa && !finished && status !== "PENDING_VERIFICATION" && (
          <Button variant="outline" onClick={() => setRaisingCapa(true)}>
            Raise CAPA
          </Button>
        )}
        {steps.map((candidate) => (
          <Button key={candidate.action} variant={candidate.outline ? "outline" : "default"} onClick={() => setStep(candidate)}>
            {candidate.label}
          </Button>
        ))}
      </PageHeader>
      {status === "PENDING_VERIFICATION" && canClose && !independent && (
        <p className="text-sm text-muted-foreground">Someone other than you must close this NCR.</p>
      )}
      <Section title="Details">
        <Details
          items={[
            ["Description", ncr.description],
            ["Source", ncr.source === "AUDIT" && ncr.auditId ? (
              <RecordLink href={`/audits/${ncr.auditId}`}>Audit</RecordLink>
            ) : ncr.source === "INSPECTION" && ncr.inspectionId ? (
              <RecordLink href={`/inspections/${ncr.inspectionId}`}>Inspection</RecordLink>
            ) : "Raised by hand"],
            ["Raised by", ncr.raisedByName],
            ["Supplier", ncr.supplierName],
            ["Material", ncr.materialName],
            ["Batch", ncr.batchNumber],
            ["Rejected quantity", ncr.rejectedQuantity && formatQuantity(ncr.rejectedQuantity)],
            ["Defect category", ncr.defectCategoryName],
            ["Assigned to", ncr.assigneeName],
            ["Containment", ncr.containmentAction],
            ["Root cause", ncr.rootCause],
            ["Outcome", ncr.outcomeNote],
          ]}
        />
      </Section>
      <Section title="Corrective actions">
        {ncr.capas.length === 0 && (
          <p className="text-sm text-muted-foreground">
            None yet.{ncr.severity !== "MINOR" && " This severity needs a closed CAPA before verification."}
          </p>
        )}
        <ul className="divide-y text-sm">
          {ncr.capas.map((capa) => (
            <li key={capa.id} className="flex items-center justify-between gap-3 py-2">
              <span>
                <RecordLink href={`/capas/${capa.id}`}>{capa.capaNumber}</RecordLink> {capa.title}
              </span>
              <StatusBadge status={capa.status} />
            </li>
          ))}
        </ul>
      </Section>
      <AttachmentPanel entityType="NCR" entityId={id} canManage={canCreate && !finished} />
      <StatusHistory changes={ncr.history} />
      {step && (
        <RecordDialog
          title={step.label}
          fields={step.fields}
          onClose={() => setStep(null)}
          onSubmit={(values) => api.post(`${path}/${step.action}`, values).then(refresh)}
        />
      )}
      {raisingCapa && (
        <RecordDialog
          title="Raise CAPA"
          fields={capaFields}
          record={{ title: ncr.title, problemStatement: ncr.description, ownerId: ncr.assignedTo ?? undefined }}
          onClose={() => setRaisingCapa(false)}
          onSubmit={async (values) => {
            const created = await api.post<Capa>("/capas", { ...values, ncrId: id, effectivenessRequired: true });
            await refresh();
            router.push(`/capas/${created.id}`);
          }}
        />
      )}
    </div>
  );
}
