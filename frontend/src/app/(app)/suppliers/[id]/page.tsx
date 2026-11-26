"use client";

import { useQuery } from "@tanstack/react-query";
import { useParams } from "next/navigation";
import { useState } from "react";
import { AttachmentPanel } from "@/components/records/attachment-panel";
import { FormError } from "@/components/forms/field";
import {
  Details,
  PageHeader,
  QueryState,
  Section,
  StatusBadge,
  useAction,
  useRefresh,
  useSupplierCategories,
} from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { SelfReviewNotice } from "@/components/app/review-policy";
import { useMe, usePermission } from "@/hooks/use-me";
import { useSelfReviewInEffect } from "@/hooks/use-organization-settings";
import { api } from "@/lib/api";
import { supplierFields } from "@/lib/record-fields";
import type { FieldDef } from "@/lib/record-form";
import type { SupplierDetail } from "@/lib/types";
import { useConfirm } from "@/components/ui/confirm-dialog";

const NOTE_FIELDS: FieldDef[] = [{ name: "note", label: "Reason", required: true }];
const CONTACT_FIELDS: FieldDef[] = [
  { name: "name", label: "Name", required: true },
  { name: "designation", label: "Designation" },
  { name: "email", label: "Email", type: "email" },
  { name: "phone", label: "Phone" },
  { name: "primary", label: "Primary contact", type: "checkbox" },
];

export default function SupplierPage() {
  const confirm = useConfirm();
  const { id } = useParams<{ id: string }>();
  const path = `/suppliers/${id}`;
  const canUpdate = usePermission("SUPPLIER_UPDATE");
  const canApprove = usePermission("SUPPLIER_APPROVE");
  const categories = useSupplierCategories();
  const refresh = useRefresh();
  const { run, pending, message } = useAction();
  const [dialog, setDialog] = useState<"edit" | "contact" | "reject" | "block" | "selfApprove" | null>(null);
  const { data: me } = useMe();
  const selfReviewInEffect = useSelfReviewInEffect();
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<SupplierDetail>(path) });
  if (!data) return <QueryState error={error} />;

  const { supplier, contacts } = data;
  const act = (action: string) => run(() => api.post(`${path}/${action}`));
  const { status } = supplier;
  // Approval is a second person's check. A sole member may approve their own only with a reason.
  const mine = supplier.submittedBy !== null && supplier.submittedBy === me?.user.id;

  return (
    <div className="space-y-6">
      <PageHeader title={supplier.name} subtitle={<StatusBadge status={status} />}>
        {canUpdate && (status === "DRAFT" || status === "REJECTED") && (
          <Button disabled={pending} onClick={() =>
              confirm({
                title: "Send this supplier for approval?",
                description: "Approvers can then approve or reject it.",
                confirmLabel: "Submit",
              }).then((ok) => {
                if (ok) void act("submit");
              })
            }>
            Submit for approval
          </Button>
        )}
        {canApprove && status === "PENDING_APPROVAL" && (
          <>
            {mine && selfReviewInEffect && (
              <Button disabled={pending} onClick={() => setDialog("selfApprove")}>
                Approve
              </Button>
            )}
            {!mine && (
            <Button disabled={pending} onClick={() =>
                confirm({
                  title: "Approve this supplier?",
                  description: "It can then be chosen on purchase orders.",
                  confirmLabel: "Approve supplier",
                }).then((ok) => {
                  if (ok) void act("approve");
                })
              }>
              Approve
            </Button>
            )}
            <Button variant="destructive" onClick={() => setDialog("reject")}>
              Reject
            </Button>
          </>
        )}
        {canApprove && status === "APPROVED" && (
          <Button variant="destructive" onClick={() => setDialog("block")}>
            Block
          </Button>
        )}
        {canApprove && status === "BLOCKED" && (
          <Button disabled={pending} onClick={() =>
              confirm({
                title: "Reinstate this supplier?",
                description: "It can be used on purchase orders again.",
                confirmLabel: "Reinstate",
              }).then((ok) => {
                if (ok) void act("reinstate");
              })
            }>
            Reinstate
          </Button>
        )}
        {canUpdate && (
          <Button variant="outline" onClick={() => setDialog("edit")}>
            Edit
          </Button>
        )}
      </PageHeader>
      <FormError message={message} />
      {canApprove && status === "PENDING_APPROVAL" && mine && !selfReviewInEffect && (
        <p className="text-sm text-muted-foreground">You submitted this supplier, so someone else must approve it.</p>
      )}
      {canApprove && status === "PENDING_APPROVAL" && mine && selfReviewInEffect && <SelfReviewNotice />}
      {supplier.statusNote && <p className="text-sm">Status note: {supplier.statusNote}</p>}
      <Section title="Details">
        <Details
          items={[
            ["Code", supplier.code],
            ["Legal name", supplier.legalName],
            ["GSTIN", supplier.gstin],
            ["Email", supplier.email],
            ["Phone", supplier.phone],
            ["Address", [supplier.address, supplier.city, supplier.state, supplier.postalCode].filter(Boolean).join(", ")],
          ]}
        />
      </Section>
      <Section
        title="Contacts"
        action={
          canUpdate && (
            <Button variant="outline" size="sm" onClick={() => setDialog("contact")}>
              Add contact
            </Button>
          )
        }
      >
        {contacts.length === 0 && <p className="text-sm text-muted-foreground">No contacts yet.</p>}
        <ul className="divide-y text-sm">
          {contacts.map((contact) => (
            <li key={contact.id} className="flex items-center justify-between gap-3 py-2">
              <span>
                {contact.name}
                {contact.primary && " (primary)"}
              </span>
              <span className="ml-auto text-muted-foreground">
                {[contact.designation, contact.email, contact.phone].filter(Boolean).join(" · ")}
              </span>
              {canUpdate && (
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() =>
                      confirm({
                        title: `Remove ${contact.name}?`,
                        description: "The contact is removed from this supplier.",
                        confirmLabel: "Remove contact",
                        danger: true,
                      }).then((ok) => {
                        if (ok) void run(() => api.delete(`${path}/contacts/${contact.id}`));
                      })
                    }
                >
                  Remove
                </Button>
              )}
            </li>
          ))}
        </ul>
      </Section>
      <AttachmentPanel entityType="SUPPLIER" entityId={id} canManage={canUpdate} />
      {dialog === "edit" && (
        <RecordDialog
          title="Edit supplier"
          fields={supplierFields(categories)}
          record={supplier}
          onClose={() => setDialog(null)}
          onSubmit={(values) => api.put(path, { ...values, version: supplier.version }).then(refresh)}
        />
      )}
      {dialog === "contact" && (
        <RecordDialog
          title="Add contact"
          fields={CONTACT_FIELDS}
          onClose={() => setDialog(null)}
          onSubmit={(values) => api.post(`${path}/contacts`, values).then(refresh)}
        />
      )}
      {dialog === "selfApprove" && (
        <RecordDialog
          title="Approve your own supplier"
          fields={[{ name: "note", label: "Why you are approving it yourself", type: "textarea", required: true }]}
          onClose={() => setDialog(null)}
          onSubmit={(values) => api.post(`${path}/approve`, values).then(refresh)}
        />
      )}
      {(dialog === "reject" || dialog === "block") && (
        <RecordDialog
          title={dialog === "reject" ? "Reject supplier" : "Block supplier"}
          fields={NOTE_FIELDS}
          onClose={() => setDialog(null)}
          onSubmit={(values) => api.post(`${path}/${dialog}`, values).then(refresh)}
        />
      )}
    </div>
  );
}
