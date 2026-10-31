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
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { supplierFields } from "@/lib/record-fields";
import type { FieldDef } from "@/lib/record-form";
import type { SupplierDetail } from "@/lib/types";

const NOTE_FIELDS: FieldDef[] = [{ name: "note", label: "Reason", required: true }];
const CONTACT_FIELDS: FieldDef[] = [
  { name: "name", label: "Name", required: true },
  { name: "designation", label: "Designation" },
  { name: "email", label: "Email", type: "email" },
  { name: "phone", label: "Phone" },
  { name: "primary", label: "Primary contact", type: "checkbox" },
];

export default function SupplierPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/suppliers/${id}`;
  const canUpdate = usePermission("SUPPLIER_UPDATE");
  const canApprove = usePermission("SUPPLIER_APPROVE");
  const categories = useSupplierCategories();
  const refresh = useRefresh();
  const { run, pending, message } = useAction();
  const [dialog, setDialog] = useState<"edit" | "contact" | "reject" | "block" | null>(null);
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<SupplierDetail>(path) });
  if (!data) return <QueryState error={error} />;

  const { supplier, contacts } = data;
  const act = (action: string) => run(() => api.post(`${path}/${action}`));
  const { status } = supplier;

  return (
    <div className="space-y-6">
      <PageHeader title={supplier.name} subtitle={<StatusBadge status={status} />}>
        {canUpdate && (status === "DRAFT" || status === "REJECTED") && (
          <Button disabled={pending} onClick={() => act("submit")}>
            Submit for approval
          </Button>
        )}
        {canApprove && status === "PENDING_APPROVAL" && (
          <>
            <Button disabled={pending} onClick={() => act("approve")}>
              Approve
            </Button>
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
          <Button disabled={pending} onClick={() => act("reinstate")}>
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
                  onClick={() => run(() => api.delete(`${path}/contacts/${contact.id}`))}
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
