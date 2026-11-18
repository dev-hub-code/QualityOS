"use client";

import { useQuery } from "@tanstack/react-query";
import {
  formatDate,
  formatMoney,
} from "@/components/billing/subscription-banner";
import {
  Section,
  StatusBadge,
  useAction,
  useRefresh,
} from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button, buttonVariants } from "@/components/ui/button";
import { FormError } from "@/components/forms/field";
import { api } from "@/lib/api";
import type { FieldDef } from "@/lib/record-form";
import type { CreditNote, Invoice } from "@/lib/types";

const REFUND_STATES: Record<string, string> = {
  PENDING: "Refund in progress",
  SUCCEEDED: "Refunded",
  FAILED: "Refund failed",
};

/**
 * Credit notes and their refunds. {@code path} is where they are listed: the organisation's own billing, or an
 * organisation in the platform console, where {@code canRefresh} lets a billing operator ask the gateway again.
 */
export function CreditNotes({
  path,
  canRefresh = false,
}: {
  path: string;
  canRefresh?: boolean;
}) {
  const { run, message } = useAction();
  const { data: notes = [] } = useQuery({
    queryKey: [`${path}/credit-notes`],
    queryFn: () => api.get<CreditNote[]>(`${path}/credit-notes`),
  });
  if (notes.length === 0) return null;
  return (
    <Section title="Credit notes">
      <FormError message={message} />
      <ul className="space-y-2 text-sm">
        {notes.map((note) => (
          <li key={note.id} className="flex flex-wrap items-center gap-2">
            <span>
              {note.creditNoteNumber}, {formatDate(note.issuedOn)}:{" "}
              {formatMoney(note.total)} against {note.invoiceNumber}.{" "}
              {note.reason}
            </span>
            {note.refund && (
              <StatusBadge
                status={
                  note.refund.status === "FAILED"
                    ? "REJECTED"
                    : note.refund.status
                }
                label={REFUND_STATES[note.refund.status]}
              />
            )}
            {note.refund?.failureReason && (
              <span className="text-muted-foreground">
                {note.refund.failureReason}
              </span>
            )}
            {canRefresh &&
              note.refund?.status === "PENDING" &&
              note.refund.provider === "CASHFREE" && (
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() =>
                    run(() =>
                      api.post(`${path}/refunds/${note.refund!.id}/refresh`),
                    )
                  }
                >
                  Check refund
                </Button>
              )}
            <a
              className={buttonVariants({ size: "sm", variant: "outline" })}
              href={`/api/v1${path}/credit-notes/${note.id}/pdf`}
            >
              PDF
            </a>
          </li>
        ))}
      </ul>
    </Section>
  );
}

const ROUTES = [
  { value: "GATEWAY", label: "Back to the card or UPI it was paid with" },
  { value: "BANK", label: "By bank transfer, already made" },
];

/** Issues a credit note against a paid invoice, with its refund. */
export function CreditNoteDialog({
  path,
  invoice,
  onClose,
}: {
  path: string;
  invoice: Invoice;
  onClose: () => void;
}) {
  const refresh = useRefresh();
  const fields: FieldDef[] = [
    {
      name: "amount",
      label: `Amount before GST (up to ${formatMoney(invoice.amount)})`,
      type: "decimal",
      required: true,
    },
    { name: "reason", label: "Reason", type: "textarea", required: true },
    {
      name: "refundBy",
      label: "Refund",
      type: "select",
      required: true,
      options: ROUTES,
    },
    { name: "reference", label: "Bank reference, for a bank transfer" },
    { name: "refundedOn", label: "Date of the bank transfer", type: "date" },
    {
      name: "endAccess",
      label: "End the period this invoice paid for, now",
      type: "checkbox",
    },
  ];
  return (
    <RecordDialog
      title={`Credit note against ${invoice.invoiceNumber}`}
      fields={fields}
      record={{ amount: invoice.amount.toFixed(2), refundBy: "GATEWAY" }}
      onClose={onClose}
      onSubmit={async (values) => {
        await api.post(`${path}/invoices/${invoice.id}/credit-notes`, {
          ...values,
          reference: values.reference || null,
          refundedOn: values.refundedOn || null,
          endAccess: Boolean(values.endAccess),
        });
        await refresh();
      }}
    />
  );
}

/** Why an invoice is for less than the plan's price, or that it was raised on its own to renew. */
export function InvoiceNote({ invoice }: { invoice: Invoice }) {
  if (invoice.prorationCredit != null) {
    return (
      <p className="text-xs text-muted-foreground">
        {formatMoney(invoice.listPrice ?? invoice.amount)} less{" "}
        {formatMoney(invoice.prorationCredit)} credit for{" "}
        {invoice.prorationNote?.replace(/^Unused/, "unused")}
        {invoice.extraDays > 0 && `, with ${invoice.extraDays} extra days`}
      </p>
    );
  }
  return invoice.renewal ? (
    <p className="text-xs text-muted-foreground">Renewal</p>
  ) : null;
}
