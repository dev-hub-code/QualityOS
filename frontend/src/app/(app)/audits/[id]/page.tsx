"use client";

import { useQuery } from "@tanstack/react-query";
import { useParams } from "next/navigation";
import { useState } from "react";
import { AuditChecklist } from "@/components/audits/audit-checklist";
import { AuditFindings } from "@/components/audits/audit-findings";
import { FormError } from "@/components/forms/field";
import { Details, PageHeader, QueryState, RecordLink, Section, StatusBadge, useAction, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import type { FieldDef } from "@/lib/record-form";
import type { Audit } from "@/lib/types";

const GRADES = [
  { value: "MAJOR_NONCONFORMITY", label: "Major nonconformity" },
  { value: "MINOR_NONCONFORMITY", label: "Minor nonconformity" },
  { value: "OBSERVATION", label: "Observation" },
  { value: "OPPORTUNITY", label: "Opportunity for improvement" },
];
const note = (label: string): FieldDef[] => [{ name: "note", label, type: "textarea", required: true }];

interface Step {
  title: string;
  action: string;
  fields: FieldDef[];
}

export default function AuditPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/audits/${id}`;
  const canManage = usePermission("AUDIT_MANAGE");
  const canClose = usePermission("AUDIT_CLOSE");
  const refresh = useRefresh();
  const { run, message } = useAction();
  const [step, setStep] = useState<Step | null>(null);
  const { data: audit, error } = useQuery({ queryKey: [path], queryFn: () => api.get<Audit>(path) });
  if (!audit) return <QueryState error={error} />;
  const { status, review } = audit;
  const inProgress = status === "IN_PROGRESS";
  const findingStep: Step = {
    title: "Record finding",
    action: "findings",
    fields: [
      { name: "grade", label: "Grade", type: "select", required: true, options: GRADES },
      {
        name: "auditItemId",
        label: "Checklist question",
        type: "select",
        options: audit.items.map((item) => ({ value: item.id, label: `${item.position}. ${item.question}` })),
      },
      { name: "statement", label: "Finding", type: "textarea", required: true },
      { name: "evidence", label: "Objective evidence", type: "textarea", required: true },
      { name: "clauseReference", label: "Clause" },
    ],
  };
  return (
    <div className="space-y-6">
      <PageHeader title={audit.auditNumber} subtitle={audit.title}>
        <StatusBadge status={status} />
        <RecordLink href={`${path}/report`}>Report</RecordLink>
      </PageHeader>
      <FormError message={message} />
      <div className="flex flex-wrap gap-2">
        {canManage && status === "PLANNED" && <Button onClick={() => run(() => api.post(`${path}/start`))}>Start audit</Button>}
        {canManage && inProgress && (
          <>
            <Button variant="outline" onClick={() => setStep(findingStep)}>
              Record finding
            </Button>
            <Button
              onClick={() =>
                setStep({
                  title: "Complete audit",
                  action: "complete",
                  fields: [{ name: "summary", label: "Summary", type: "textarea", required: true }],
                })
              }
            >
              Complete audit
            </Button>
          </>
        )}
        {canManage && (status === "PLANNED" || inProgress) && (
          <Button variant="outline" onClick={() => setStep({ title: "Cancel audit", action: "cancel", fields: note("Reason") })}>
            Cancel audit
          </Button>
        )}
        {canManage && status === "COMPLETED" && (
          <Button onClick={() => setStep({ title: "Request closure", action: "request-closure", fields: note("Closure note") })}>
            Request closure
          </Button>
        )}
        {canClose && status === "PENDING_CLOSURE" && (
          <>
            <Button onClick={() => run(() => api.post(`${path}/close`))}>Approve closure</Button>
            <Button variant="outline" onClick={() => setStep({ title: "Send back", action: "send-back", fields: note("What still needs doing") })}>
              Send back
            </Button>
          </>
        )}
      </div>
      <Section title="Audit">
        <Details
          items={[
            ["Type", audit.auditType.toLowerCase()],
            ["Facility", audit.facilityName],
            ["Planned for", audit.plannedOn],
            ["Lead auditor", audit.leadAuditorName],
            ["Checklist", audit.checklistName],
            ["Scope", audit.scope],
            ...(audit.supplierName ? [["Supplier", audit.supplierName] as [string, string]] : []),
            ...(audit.productName ? [["Product", audit.productName] as [string, string]] : []),
            ...(audit.summary ? [["Summary", audit.summary] as [string, string]] : []),
            ...(audit.closureNote ? [["Closure note", audit.closureNote] as [string, string]] : []),
            ...(audit.closedByName ? [["Closed by", audit.closedByName] as [string, string]] : []),
            ...(audit.cancelReason ? [["Cancelled because", audit.cancelReason] as [string, string]] : []),
          ]}
        />
      </Section>
      {review.blockers.length > 0 && (
        <Section title="Before this audit can close">
          <ul className="list-disc pl-5 text-sm">
            {review.blockers.map((blocker) => (
              <li key={blocker}>{blocker}</li>
            ))}
          </ul>
        </Section>
      )}
      <Section title={`Checklist (${review.answered} of ${review.items} answered)`}>
        <AuditChecklist auditId={id} items={audit.items} editable={canManage && inProgress} />
      </Section>
      <Section title="Findings">
        <AuditFindings
          auditId={id}
          findings={audit.findings}
          canRaise={canManage && (inProgress || status === "COMPLETED")}
          canAttach={canManage && !["CLOSED", "CANCELLED"].includes(status)}
        />
      </Section>
      {step && (
        <RecordDialog
          title={step.title}
          fields={step.fields}
          onClose={() => setStep(null)}
          onSubmit={async (values) => {
            await api.post(`${path}/${step.action}`, values);
            refresh();
          }}
        />
      )}
    </div>
  );
}
