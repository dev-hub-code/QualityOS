"use client";

import { useRouter } from "next/navigation";
import { useState, type ReactNode, useEffect } from "react";
import { FormError } from "@/components/forms/field";
import { Details, PageHeader, Section, StatusBadge, useAction, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { useOwnApprovalRefused } from "@/hooks/use-own-approval";
import { DEFINITIONS, revisionActions, type DefinitionKind } from "@/lib/manufacturing";
import { formatQuantity, type FieldDef } from "@/lib/record-form";
import type { Revision } from "@/lib/types";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { formatDateTime } from "@/lib/dates";

const when = (value: string | null) => (value ? formatDateTime(value) : "–");

/** The revision's title, its stage, and the workflow actions open to this person. */
export function RevisionHeader({
  kind,
  revision,
  unit,
  details = [],
}: {
  kind: DefinitionKind;
  revision: Revision;
  unit: string;
  details?: [string, ReactNode][];
}) {
  const confirm = useConfirm();
  const definition = DEFINITIONS[kind];
  const path = `${definition.path}/${revision.id}`;
  const canManage = usePermission(definition.manage);
  const canApprove = usePermission(definition.approve);
  const refresh = useRefresh();
  const router = useRouter();
  const { run, pending, message, clear } = useAction();
  // A refusal such as "Add at least one line" stops being true once the revision changes, so it is not left showing.
  useEffect(clear, [revision]); // eslint-disable-line react-hooks/exhaustive-deps
  const [dialog, setDialog] = useState<"edit" | "sendBack" | "approve" | "retire" | null>(null);
  const ownRefused = useOwnApprovalRefused(revision.submittedBy);
  const actions = revisionActions(revision.status, revision.submittedAt !== null, { manage: canManage, approve: canApprove })
    .filter((action) => action !== "approve" || !ownRefused);
  const close = () => setDialog(null);

  const editFields: FieldDef[] = [{ name: "changeNote", label: "What this revision is for", type: "textarea" }];
  if (kind !== "ROUTING") {
    editFields.push({ name: "baseQuantity", label: kind === "RECIPE" ? "Batch size" : "Quantity the lines make", type: "decimal" });
  }

  return (
    <>
      <PageHeader
        title={`${revision.productCode} – ${definition.one.charAt(0).toUpperCase()}${definition.one.slice(1)} revision ${revision.revision}`}
        subtitle={
          <span className="flex flex-wrap items-center gap-2">
            <StatusBadge status={revision.status} />
            {revision.inEffect && <StatusBadge status="IN_EFFECT" />}
            {revision.productName}
          </span>
        }
      >
        {actions.includes("edit") && (
          <Button variant="outline" onClick={() => setDialog("edit")}>
            Edit
          </Button>
        )}
        {actions.includes("submit") && (
          <Button disabled={pending} onClick={() =>
              confirm({
                title: "Submit this revision for approval?",
                description: "Approvers are notified, and it can no longer be edited unless it is sent back.",
                confirmLabel: "Submit",
              }).then((ok) => {
                if (ok) void run(() => api.post(`${path}/submit`));
              })
            }>
            Submit for approval
          </Button>
        )}
        {actions.includes("discard") && (
          <Button
            variant="outline"
            disabled={pending}
            onClick={() =>
                confirm({
                  title: "Discard this draft?",
                  description: "The draft and everything on it is deleted. This cannot be undone.",
                  confirmLabel: "Discard draft",
                  danger: true,
                }).then((ok) => {
                  if (ok) void run(async () => { await api.delete(path); router.push(definition.path); });
                })
              }
          >
            Discard draft
          </Button>
        )}
        {actions.includes("approve") && <Button onClick={() => setDialog("approve")}>Approve</Button>}
        {actions.includes("sendBack") && (
          <Button variant="outline" onClick={() => setDialog("sendBack")}>
            Send back
          </Button>
        )}
        {actions.includes("retire") && (
          <Button variant="destructive" onClick={() => setDialog("retire")}>
            Retire
          </Button>
        )}
        {canManage && (revision.status === "APPROVED" || revision.status === "OBSOLETE") && (
          <Button
            variant="outline"
            disabled={pending}
            onClick={() =>
              run(async () => {
                const created = await api.post<{ revision: Revision }>(definition.path, {
                  productId: revision.productId,
                  copyFromId: revision.id,
                });
                router.push(`${definition.path}/${created.revision.id}`);
              })
            }
          >
            New revision from this
          </Button>
        )}
      </PageHeader>
      {revision.status === "SUBMITTED" && canApprove && ownRefused && (
        <p className="text-sm text-muted-foreground">You submitted this revision, so someone else must approve it.</p>
      )}
      <FormError message={message} />
      {revision.reviewNote && revision.status === "DRAFT" && (
        <p className="rounded-xl bg-amber-50 px-3 py-2 text-sm text-amber-900 ring-1 ring-amber-200">
          Sent back: {revision.reviewNote}
        </p>
      )}
      <Section title="Revision">
        <Details
          items={[
            ...(revision.baseQuantity !== null
              ? ([[kind === "RECIPE" ? "Batch size" : "Makes", `${formatQuantity(revision.baseQuantity)} ${unit}`]] as [string, ReactNode][])
              : []),
            ...details,
            ["What it is for", revision.changeNote ?? "–"],
            ["Effective", revision.effectiveFrom ? `${revision.effectiveFrom} to ${revision.effectiveTo ?? "open"}` : "Not yet approved"],
            ["Submitted", revision.submittedAt ? `${revision.submittedByName ?? "Former member"}, ${when(revision.submittedAt)}` : "–"],
            ["Approved", revision.approvedAt ? `${revision.approvedByName ?? "Former member"}, ${when(revision.approvedAt)}` : "–"],
          ]}
        />
      </Section>
      {dialog === "edit" && (
        <RecordDialog title="Edit revision" fields={editFields} record={revision} onClose={close} onSubmit={(values) => api.put(path, values).then(refresh)} />
      )}
      {dialog === "sendBack" && (
        <RecordDialog
          title="Send back to draft"
          fields={[{ name: "note", label: "What needs changing", type: "textarea", required: true }]}
          onClose={close}
          onSubmit={(values) => api.post(`${path}/send-back`, values).then(refresh)}
        />
      )}
      {dialog === "approve" && (
        <RecordDialog
          title={`Approve ${definition.one} revision ${revision.revision}`}
          fields={[
            { name: "effectiveFrom", label: "Takes effect on (today if left blank)", type: "date" },
            { name: "note", label: "Note", type: "textarea" },
          ]}
          onClose={close}
          onSubmit={(values) => api.post(`${path}/approve`, values).then(refresh)}
        />
      )}
      {dialog === "retire" && (
        <RecordDialog
          title="Retire revision"
          fields={[{ name: "reason", label: "Why it is no longer used", type: "textarea", required: true }]}
          onClose={close}
          onSubmit={(values) => api.post(`${path}/retire`, values).then(refresh)}
        />
      )}
    </>
  );
}
