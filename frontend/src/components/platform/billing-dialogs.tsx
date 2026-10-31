"use client";

import { useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { api } from "@/lib/api";
import type { FieldDef } from "@/lib/record-form";

export const REASON_FIELD: FieldDef = { name: "reason", label: "Reason", type: "textarea", required: true };

export const EXTEND_FIELDS: FieldDef[] = [
  { name: "days", label: "Extra days", type: "decimal", required: true },
  REASON_FIELD,
];

const PAYMENT_METHODS = [
  { value: "BANK_TRANSFER", label: "Bank transfer" },
  { value: "UPI", label: "UPI" },
  { value: "NEFT", label: "NEFT" },
  { value: "RTGS", label: "RTGS" },
  { value: "IMPS", label: "IMPS" },
  { value: "CHEQUE", label: "Cheque" },
  { value: "OTHER", label: "Other" },
];

const PAYMENT_FIELDS: FieldDef[] = [
  { name: "reference", label: "Bank or UTR reference", required: true },
  { name: "amount", label: "Amount received", type: "decimal", required: true },
  { name: "method", label: "Payment method", type: "select", required: true, options: PAYMENT_METHODS },
  { name: "paidOn", label: "Payment date", type: "date", required: true },
  { name: "notes", label: "Notes", type: "textarea" },
];

interface RecordPaymentDialogProps {
  tenantId: string;
  invoiceId: string;
  invoiceNumber: string;
  amount: number;
  onClose: () => void;
}

export function RecordPaymentDialog({ tenantId, invoiceId, invoiceNumber, amount, onClose }: RecordPaymentDialogProps) {
  const refresh = useRefresh();
  return (
    <RecordDialog
      title={`Record payment for ${invoiceNumber}`}
      fields={PAYMENT_FIELDS}
      record={{ amount: amount.toFixed(2), method: "NEFT", paidOn: new Date().toLocaleDateString("en-CA") }}
      onClose={onClose}
      onSubmit={async (values) => {
        await api.post(`/platform/organisations/${tenantId}/invoices/${invoiceId}/payments`, {
          ...values,
          failed: false,
        });
        await refresh();
      }}
    />
  );
}

interface ReasonDialogProps {
  title: string;
  path: string;
  onClose: () => void;
}

/** Every operator action says why, and the reason stays with the organisation's audit trail. */
export function ReasonDialog({ title, path, onClose }: ReasonDialogProps) {
  const refresh = useRefresh();
  return (
    <RecordDialog
      title={title}
      fields={[REASON_FIELD]}
      onClose={onClose}
      onSubmit={async (values) => {
        await api.post(path, values);
        await refresh();
      }}
    />
  );
}
