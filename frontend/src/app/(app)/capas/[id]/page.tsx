"use client";

import { useQuery } from "@tanstack/react-query";
import { useParams } from "next/navigation";
import { useState } from "react";
import { FormError } from "@/components/forms/field";
import { AttachmentPanel } from "@/components/records/attachment-panel";
import { CapaAnalysisForm } from "@/components/records/capa-analysis-form";
import {
  Details,
  PageHeader,
  QueryState,
  RecordLink,
  Section,
  StatusBadge,
  useAction,
  useAssignees,
  useRefresh,
} from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { StatusHistory } from "@/components/records/status-history";
import { Button } from "@/components/ui/button";
import { useMe, usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { FISHBONE_BRANCHES, isReadyForVerification } from "@/lib/capa";
import type { FieldDef } from "@/lib/record-form";
import type { Capa, CapaAction } from "@/lib/types";

interface Step {
  title: string;
  path: string;
  fields: FieldDef[];
  build?: (values: Record<string, unknown>) => unknown;
}

const noteField = (label: string): FieldDef => ({ name: "note", label, type: "textarea", required: true });

export default function CapaPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/capas/${id}`;
  const refresh = useRefresh();
  const { data: me } = useMe();
  const canManage = usePermission("CAPA_MANAGE");
  const canApprove = usePermission("CAPA_APPROVE");
  const assignees = useAssignees();
  const { run, pending, message } = useAction();
  const [step, setStep] = useState<Step | null>(null);
  const { data: capa, error } = useQuery({ queryKey: [path], queryFn: () => api.get<Capa>(path) });
  if (!capa) return <QueryState error={error} />;

  const { status } = capa;
  const finished = status === "CLOSED" || status === "CANCELLED";
  const independent = me?.user.id !== capa.ownerId;
  const latest = capa.verifications.at(-1);
  const verified = !capa.effectivenessRequired || latest?.effective === true;
  const post = (action: string) => run(() => api.post(`${path}/${action}`));

  const addAction: Step = {
    title: "Add action",
    path: `${path}/actions`,
    fields: [
      {
        name: "actionType",
        label: "Type",
        type: "select",
        required: true,
        options: [
          { value: "CORRECTIVE", label: "Corrective" },
          { value: "PREVENTIVE", label: "Preventive" },
        ],
      },
      { name: "description", label: "Description", type: "textarea", required: true },
      { name: "ownerId", label: "Owner", type: "select", required: true, options: assignees },
      { name: "targetDate", label: "Target date", type: "date", required: true },
    ],
  };
  const complete = (action: CapaAction): Step => ({
    title: "Complete action",
    path: `${path}/actions/${action.id}/complete`,
    fields: [noteField("What was done")],
  });
  const verify: Step = {
    title: "Verify effectiveness",
    path: `${path}/verify`,
    fields: [
      {
        name: "effective",
        label: "Result",
        type: "select",
        required: true,
        options: [
          { value: "true", label: "Effective" },
          { value: "false", label: "Not effective" },
        ],
      },
      { name: "method", label: "How it was checked", required: true },
      { name: "note", label: "Findings", type: "textarea", required: true },
    ],
    build: (values) => ({ ...values, effective: values.effective === "true" }),
  };

  return (
    <div className="space-y-6">
      <PageHeader
        title={capa.capaNumber}
        subtitle={
          <span className="flex flex-wrap items-center gap-2">
            <StatusBadge status={status} />
            {capa.overdue && <StatusBadge status="OVERDUE" />}
            {capa.title}
          </span>
        }
      >
        {canManage && status === "OPEN" && (
          <Button disabled={pending} onClick={() => post("start-analysis")}>
            Start analysis
          </Button>
        )}
        {canManage && status === "ACTION_PLANNING" && (
          <>
            <Button variant="outline" onClick={() => setStep(addAction)}>
              Add action
            </Button>
            <Button disabled={pending} onClick={() => post("start-implementation")}>
              Start implementation
            </Button>
          </>
        )}
        {canManage && status === "IMPLEMENTATION" && isReadyForVerification(capa.actions) && (
          <Button disabled={pending} onClick={() => post("submit-verification")}>
            Submit for verification
          </Button>
        )}
        {canApprove && status === "EFFECTIVENESS_VERIFICATION" && independent && (
          <>
            <Button variant={verified ? "outline" : "default"} onClick={() => setStep(verify)}>
              Verify effectiveness
            </Button>
            {verified && (
              <Button
                onClick={() => setStep({ title: "Close CAPA", path: `${path}/close`, fields: [noteField("Closure note")] })}
              >
                Close CAPA
              </Button>
            )}
          </>
        )}
        {canApprove && !finished && (
          <Button
            variant="outline"
            onClick={() => setStep({ title: "Cancel CAPA", path: `${path}/cancel`, fields: [noteField("Reason")] })}
          >
            Cancel CAPA
          </Button>
        )}
      </PageHeader>
      <FormError message={message} />
      {status === "EFFECTIVENESS_VERIFICATION" && canApprove && !independent && (
        <p className="text-sm text-muted-foreground">Someone other than the CAPA owner must verify this.</p>
      )}
      <Section title="Details">
        <Details
          items={[
            ["Problem", capa.problemStatement],
            ["NCR", capa.ncrId && <RecordLink href={`/ncrs/${capa.ncrId}`}>{capa.ncrNumber}</RecordLink>],
            ["Owner", capa.ownerName],
            ["Target date", capa.targetDate],
            ["Effectiveness check", capa.effectivenessRequired ? "Required" : "Not required"],
            ["Outcome", capa.outcomeNote],
          ]}
        />
      </Section>
      {status === "ROOT_CAUSE_ANALYSIS" && canManage ? (
        <CapaAnalysisForm capa={capa} />
      ) : (
        capa.rootCause && (
          <Section title="Root cause">
            <p className="text-sm">{capa.rootCause}</p>
            {capa.fiveWhys.length > 0 && (
              <ol className="mt-3 list-decimal space-y-1 pl-5 text-sm">
                {capa.fiveWhys.map((why) => (
                  <li key={why}>{why}</li>
                ))}
              </ol>
            )}
            {FISHBONE_BRANCHES.filter((branch) => capa.fishbone[branch]).map((branch) => (
              <p key={branch} className="mt-2 text-sm">
                <span className="font-medium">{branch}:</span> {capa.fishbone[branch].join("; ")}
              </p>
            ))}
          </Section>
        )
      )}
      <Section title="Actions">
        {capa.actions.length === 0 && <p className="text-sm text-muted-foreground">No actions yet.</p>}
        <ul className="divide-y text-sm">
          {capa.actions.map((action) => (
            <li key={action.id} className="flex flex-wrap items-center justify-between gap-3 py-2">
              <span>
                <span className="font-medium">{action.actionType === "CORRECTIVE" ? "Corrective" : "Preventive"}:</span>{" "}
                {action.description} · {action.ownerName} · due {action.targetDate}
                {action.completionNote && ` · done: ${action.completionNote}`}
              </span>
              {canManage && status === "IMPLEMENTATION" && !action.completedAt && (
                <Button variant="outline" size="sm" onClick={() => setStep(complete(action))}>
                  Complete
                </Button>
              )}
            </li>
          ))}
        </ul>
      </Section>
      {capa.verifications.length > 0 && (
        <Section title="Effectiveness verification">
          <ul className="divide-y text-sm">
            {capa.verifications.map((verdict) => (
              <li key={verdict.at} className="py-2">
                {verdict.effective ? "Effective" : "Not effective"} ({verdict.method}): {verdict.note}. Checked by{" "}
                {verdict.verifiedBy}
              </li>
            ))}
          </ul>
        </Section>
      )}
      <AttachmentPanel entityType="CAPA" entityId={id} canManage={canManage && !finished} />
      <StatusHistory changes={capa.history} />
      {step && (
        <RecordDialog
          title={step.title}
          fields={step.fields}
          onClose={() => setStep(null)}
          onSubmit={(values) => api.post(step.path, step.build ? step.build(values) : values).then(refresh)}
        />
      )}
    </div>
  );
}
