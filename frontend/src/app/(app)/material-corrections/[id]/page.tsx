"use client";

import { useQuery } from "@tanstack/react-query";
import { useParams } from "next/navigation";
import { useState } from "react";
import { FormError } from "@/components/forms/field";
import {
  Details,
  PageHeader,
  QueryState,
  Section,
  StatusBadge,
  useAction,
  useRefresh,
} from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { useMe, usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { humanize } from "@/lib/capa";
import type { FieldDef } from "@/lib/record-form";
import type { CorrectionDetail } from "@/lib/types";

const noteField: FieldDef = { name: "note", label: "Note", type: "textarea", required: true };

export default function MaterialCorrectionPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/material-corrections/${id}`;
  const refresh = useRefresh();
  const { data: me } = useMe();
  const canApprove = usePermission("MATERIAL_CORRECTION_APPROVE");
  const { run, pending, message } = useAction();
  const [dialog, setDialog] = useState<"approve" | "reject" | "cancel" | null>(null);
  const { data: correction, error } = useQuery({ queryKey: [path], queryFn: () => api.get<CorrectionDetail>(path) });
  if (!correction) return <QueryState error={error} />;

  const { status, impact } = correction;
  const mine = me?.user.id === correction.requestedBy;
  const awaiting = status === "PENDING_APPROVAL";
  const reachedFurther = Boolean(impact && (impact.inspected || impact.batches.length || impact.customers.length));
  const approveFields: FieldDef[] = [
    { name: "note", label: "Note", type: "textarea" },
    ...(reachedFurther
      ? [{ name: "impactAcknowledged", label: "I have reviewed the impact below", type: "checkbox" } as FieldDef]
      : []),
  ];
  return (
    <div className="space-y-6">
      <PageHeader
        title={correction.correctionNumber}
        subtitle={
          <span className="flex flex-wrap items-center gap-2">
            <StatusBadge status={status} />
            {correction.subject}
          </span>
        }
      >
        {mine && status === "DRAFT" && (
          <Button disabled={pending} onClick={() => run(() => api.post(`${path}/submit`))}>
            Submit for approval
          </Button>
        )}
        {canApprove && awaiting && !mine && (
          <>
            <Button onClick={() => setDialog("approve")}>Approve</Button>
            <Button variant="outline" onClick={() => setDialog("reject")}>
              Reject
            </Button>
          </>
        )}
        {mine && (status === "DRAFT" || awaiting) && (
          <Button variant="outline" onClick={() => setDialog("cancel")}>
            Cancel request
          </Button>
        )}
      </PageHeader>
      <FormError message={message} />
      {awaiting && mine && <p className="text-sm text-muted-foreground">Someone other than you must approve this.</p>}
      <Section title="Details">
        <Details
          items={[
            ["Reason", correction.reason],
            ["Requested by", correction.requestedByName],
            ["Approvals", `${correction.approvals.length} of ${correction.approvalsNeeded}`],
            ["Decision note", correction.decisionNote],
          ]}
        />
      </Section>
      <Section title="Changes">
        <ul className="divide-y text-sm">
          {correction.changes.map((change) => (
            <li key={change.field} className="py-2">
              <span className="font-medium">{humanize(change.field)}:</span> {change.originalValue ?? "—"} →{" "}
              {change.correctedValue}
            </li>
          ))}
        </ul>
      </Section>
      {reachedFurther && impact && (
        <Section title="Impact">
          <ul className="space-y-1 text-sm">
            {impact.inspected && <li>This lot has an inspection on record.</li>}
            {impact.batches.length > 0 && <li>Used in: {impact.batches.join(", ")}</li>}
            {impact.customers.length > 0 && <li>Customers holding this material: {impact.customers.join(", ")}</li>}
          </ul>
        </Section>
      )}
      {correction.approvals.length > 0 && (
        <Section title="Approvals">
          <ul className="divide-y text-sm">
            {correction.approvals.map((approval) => (
              <li key={approval.at} className="py-2">
                {approval.approverName} on {new Date(approval.at).toLocaleString()}
                {approval.note && `: ${approval.note}`}
              </li>
            ))}
          </ul>
        </Section>
      )}
      {dialog === "approve" && (
        <RecordDialog
          title="Approve correction"
          fields={approveFields}
          onClose={() => setDialog(null)}
          onSubmit={(values) =>
            api
              .post(`${path}/approve`, { note: values.note, impactAcknowledged: Boolean(values.impactAcknowledged) })
              .then(refresh)
          }
        />
      )}
      {(dialog === "reject" || dialog === "cancel") && (
        <RecordDialog
          title={dialog === "reject" ? "Reject correction" : "Cancel request"}
          fields={[noteField]}
          onClose={() => setDialog(null)}
          onSubmit={(values) => api.post(`${path}/${dialog}`, values).then(refresh)}
        />
      )}
    </div>
  );
}
