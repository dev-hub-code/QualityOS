"use client";

import { useState } from "react";
import { FormError } from "@/components/forms/field";
import { AttachmentPanel } from "@/components/records/attachment-panel";
import { StatusBadge, useAction, useAssignees, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { formatQuantity, type FieldDef } from "@/lib/record-form";
import type { RecallContact } from "@/lib/types";

const METHODS = ["EMAIL", "PHONE", "WHATSAPP", "OTHER"].map((value) => ({
  value,
  label: value === "WHATSAPP" ? "WhatsApp" : value[0] + value.slice(1).toLowerCase(),
}));
const OUTCOMES = [
  { value: "NO_REPLY", label: "Sent, no reply yet" },
  { value: "REACHED", label: "Spoke to someone" },
  { value: "FAILED", label: "Could not get through" },
];
const ATTEMPT_FIELDS: FieldDef[] = [
  { name: "method", label: "Method", type: "select", required: true, options: METHODS },
  { name: "attemptedOn", label: "Date", type: "date", required: true },
  { name: "outcome", label: "Outcome", type: "select", required: true, options: OUTCOMES },
  { name: "contactPerson", label: "Person" },
  { name: "contactDetails", label: "Number or address used" },
  { name: "customerResponse", label: "What they said", type: "textarea" },
  { name: "notes", label: "Notes", type: "textarea" },
  { name: "followUpOn", label: "Follow up on", type: "date" },
];
const ACKNOWLEDGEMENT_FIELDS: FieldDef[] = [
  { name: "acknowledgedOn", label: "Acknowledged on", type: "date", required: true },
  { name: "acknowledgedBy", label: "Acknowledged by", required: true },
  { name: "customerResponse", label: "What they said", type: "textarea" },
];
const EXCEPTION_FIELDS: FieldDef[] = [{ name: "reason", label: "Why this customer cannot be reached", type: "textarea", required: true }];
const NEXT_STATUSES = [
  ["ACTION_REQUIRED", "Action required"],
  ["RETURN_IN_PROGRESS", "Return in progress"],
  ["RESOLVED", "Resolved"],
  ["UNREACHABLE", "Unreachable"],
];

/** The API wants a moment, so a date means midday, or now if midday has not come yet. */
export function attemptMoment(date: string, now = new Date()): string {
  const midday = new Date(`${date}T12:00:00`);
  return (midday > now ? now : midday).toISOString();
}

type Dialog = "attempt" | "acknowledge" | "edit" | "exception";

export function ContactTable({
  recallId,
  contacts,
  open,
}: {
  recallId: string;
  contacts: RecallContact[];
  open: boolean;
}) {
  const canManage = usePermission("RECALL_MANAGE") && open;
  const canClose = usePermission("RECALL_CLOSE") && open;
  const canSeeFiles = usePermission("RECALL_VIEW");
  const assignees = useAssignees();
  const refresh = useRefresh();
  const { run, message } = useAction();
  const [active, setActive] = useState<{ contact: RecallContact; dialog: Dialog } | null>(null);
  const [evidence, setEvidence] = useState<string | null>(null);
  const base = `/recalls/${recallId}`;
  const customer = (contact: RecallContact) => `${base}/customers/${contact.customerId}`;
  const close = () => setActive(null);
  const editFields: FieldDef[] = [
    { name: "contactPerson", label: "Contact person" },
    { name: "contactDetails", label: "Contact details" },
    { name: "assignedTo", label: "Assigned to", type: "select", options: assignees },
    { name: "followUpOn", label: "Follow up on", type: "date" },
    { name: "notes", label: "Notes", type: "textarea" },
  ];

  return (
    <div className="space-y-3">
      <FormError message={message} />
      {contacts.length === 0 && <p className="text-sm text-muted-foreground">No customers match.</p>}
      <ul className="divide-y rounded-lg border text-sm">
        {contacts.map((contact) => (
          <li key={contact.id} className="space-y-2 p-3">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-medium break-words">{contact.customerName}</span>
              <StatusBadge status={contact.status} />
              {contact.exceptionReason && <StatusBadge status="EXCEPTION" />}
            </div>
            <p className="text-muted-foreground break-words">
              {[contact.contactPerson, contact.contactDetails].filter(Boolean).join(", ") || "No contact details"}
              {contact.assignedName && ` · Assigned to ${contact.assignedName}`}
              {contact.followUpOn && ` · Follow up on ${contact.followUpOn}`}
            </p>
            <p>
              {formatQuantity(contact.quantity)} dispatched, {formatQuantity(contact.returnedQuantity)} returned,{" "}
              {formatQuantity(contact.outstandingQuantity)} still out · {contact.attempts} attempts
              {contact.acknowledgedOn && ` · Acknowledged ${contact.acknowledgedOn}`}
            </p>
            {contact.exceptionReason && <p>Exception: {contact.exceptionReason}</p>}
            <div className="flex flex-wrap items-center gap-2">
              {canManage && (
                <>
                  <Button size="sm" variant="outline" onClick={() => setActive({ contact, dialog: "attempt" })}>
                    Log attempt
                  </Button>
                  <Button size="sm" variant="outline" onClick={() => setActive({ contact, dialog: "acknowledge" })}>
                    Record acknowledgement
                  </Button>
                  <Button size="sm" variant="ghost" onClick={() => setActive({ contact, dialog: "edit" })}>
                    Edit
                  </Button>
                  <select
                    aria-label={`Move ${contact.customerName} to`}
                    className="h-8 rounded-md border bg-background px-2 text-sm"
                    value=""
                    onChange={(event) => {
                      // Read now: this select snaps back to blank before the request is built.
                      const status = event.target.value;
                      run(() => api.post(`${customer(contact)}/status`, { status }));
                    }}
                  >
                    <option value="">Move to…</option>
                    {NEXT_STATUSES.map(([value, label]) => (
                      <option key={value} value={value}>
                        {label}
                      </option>
                    ))}
                  </select>
                </>
              )}
              {canClose && contact.status === "UNREACHABLE" && !contact.exceptionReason && (
                <Button size="sm" variant="outline" onClick={() => setActive({ contact, dialog: "exception" })}>
                  Record exception
                </Button>
              )}
              {canSeeFiles && (
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => setEvidence(evidence === contact.id ? null : contact.id)}
                >
                  Evidence
                </Button>
              )}
            </div>
            {evidence === contact.id && (
              <AttachmentPanel entityType="RECALL_CONTACT" entityId={contact.id} canManage={canManage} />
            )}
          </li>
        ))}
      </ul>
      {active?.dialog === "attempt" && (
        <RecordDialog
          title={`Contact attempt: ${active.contact.customerName}`}
          fields={ATTEMPT_FIELDS}
          onClose={close}
          onSubmit={async ({ attemptedOn, ...values }) => {
            await api.post(`${customer(active.contact)}/contact-attempts`, {
              ...values,
              attemptedAt: attemptMoment(String(attemptedOn)),
            });
            refresh();
          }}
        />
      )}
      {active?.dialog === "acknowledge" && (
        <RecordDialog
          title={`Acknowledgement: ${active.contact.customerName}`}
          fields={ACKNOWLEDGEMENT_FIELDS}
          onClose={close}
          onSubmit={async (values) => {
            await api.post(`${base}/acknowledgements`, { ...values, customerId: active.contact.customerId });
            refresh();
          }}
        />
      )}
      {active?.dialog === "edit" && (
        <RecordDialog
          title={`Edit: ${active.contact.customerName}`}
          fields={editFields}
          record={active.contact as unknown as Record<string, unknown>}
          onClose={close}
          onSubmit={async (values) => {
            await api.put(customer(active.contact), values);
            refresh();
          }}
        />
      )}
      {active?.dialog === "exception" && (
        <RecordDialog
          title={`Exception: ${active.contact.customerName}`}
          fields={EXCEPTION_FIELDS}
          onClose={close}
          onSubmit={async (values) => {
            await api.post(`${customer(active.contact)}/exception`, values);
            refresh();
          }}
        />
      )}
    </div>
  );
}
