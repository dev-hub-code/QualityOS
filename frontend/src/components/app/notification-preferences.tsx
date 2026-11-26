"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { FormError } from "@/components/forms/field";
import { Checkbox } from "@/components/ui/checkbox";
import { Label } from "@/components/ui/label";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import type { NotificationPreference } from "@/lib/types";

const LABELS: Record<string, string> = {
  MATERIAL_AWAITING_INSPECTION: "Received material is waiting for inspection",
  INSPECTION_AWAITING_APPROVAL: "An inspection is waiting for approval",
  INSPECTION_CRITICAL_FAILURE: "A critical parameter fails",
  INSPECTION_SENT_BACK: "My inspection is sent back",
  INSPECTION_AWAITING_DISPOSITION: "An inspection needs a disposition",
  INSPECTION_DISPOSITION_RECORDED: "A disposition is recorded on my inspection",
  MATERIAL_CORRECTION_AWAITING_APPROVAL: "A material correction needs approval",
  MATERIAL_CORRECTION_DECIDED: "A material correction is approved or rejected",
  NCR_RAISED: "An NCR is raised",
  NCR_ASSIGNED: "An NCR is assigned to me",
  NCR_AWAITING_CLOSURE: "An NCR is ready for closure",
  CAPA_ASSIGNED: "A CAPA is assigned to me or returned to planning",
  CAPA_ACTION_ASSIGNED: "A CAPA action is assigned to me",
  CAPA_AWAITING_VERIFICATION: "A CAPA needs effectiveness verification",
  CAPA_DUE_SOON: "My CAPA is due soon",
  CAPA_OVERDUE: "My CAPA is overdue",
  CAPA_ACTION_OVERDUE: "An action I own, or on my CAPA, is overdue",
  CAPA_ESCALATED:
    "A CAPA or one of its actions is long overdue (for CAPA approvers)",
  CAPA_CLOSED: "My CAPA is closed",
  RECALL_UNANSWERED: "Customers on a recall have not acknowledged it in time",
  RECALL_AWAITING_CLOSURE: "A recall is ready to close",
  RECALL_SENT_BACK: "A recall closure you asked for is sent back",
  DISPATCH_SHIPMENT_UNCONFIRMED:
    "Dispatches are waiting for shipment confirmation",
  CALIBRATION_DUE_SOON: "An instrument is due for calibration",
  CALIBRATION_OVERDUE: "An instrument is overdue for calibration",
  CALIBRATION_FAILED: "An instrument fails calibration",
  AUDIT_SCHEDULED: "An audit is scheduled for me",
  AUDIT_DUE_SOON: "An audit I lead is a week away",
  AUDIT_FINDINGS: "An audit has findings",
  AUDIT_AWAITING_CLOSURE: "An audit is ready for closure",
  SUPPLIER_AWAITING_APPROVAL: "A supplier needs approval",
  SUPPLIER_REJECTED: "A supplier I submitted is rejected",
  WORK_ORDER_AWAITING_APPROVAL: "A work order needs approval",
  WORK_ORDER_SENT_BACK: "My work order is sent back",
  DEFINITION_AWAITING_APPROVAL: "A BOM, recipe or routing needs approval",
  DEFINITION_SENT_BACK: "My BOM, recipe or routing is sent back",
  REWORK_AWAITING_APPROVAL: "A rework order needs approval",
  REWORK_AWAITING_INSPECTION: "Reworked goods are ready for reinspection",
  PRODUCTION_QUALITY_HOLD: "A work order goes on quality hold",
  OPERATION_BLOCKED: "An operation is blocked on the shop floor",
  PROCESS_OUT_OF_LIMITS: "A process reading is outside its limits",
  MACHINE_BREAKDOWN: "A machine breaks down",
  WORK_ORDER_OVERDUE: "A work order is past its planned end",
  PLAN_LINE_OVERDUE: "A production plan line is past its due date",
  ANNOUNCEMENT: "ProductionOS sends an announcement",
  SUPPORT_TICKET_UPDATED: "Support replies to my request or changes its status",
  SUPPORT_CHANGES_STARTED:
    "Support starts a session that can make changes in our organisation",
  REPORT_EXPORT_READY: "A report I exported is ready, or could not be made",
  SUBSCRIPTION_EXPIRY: "Our subscription is about to end",
  PAYMENT_RECEIVED: "A subscription payment is received",
  REFUND_ISSUED: "A subscription payment is refunded",
  RENEWAL_INVOICE: "A renewal invoice is issued",
  INVOICE_OVERDUE: "A subscription invoice is overdue",
  PLAN_CHANGED: "Our plan changes",
  PLAN_LIMIT_WARNING: "We are close to a plan limit",
};
/** Billing mail goes only to those who manage the subscription, so only they are offered it. */
const BILLING = new Set([
  "SUBSCRIPTION_EXPIRY",
  "PAYMENT_RECEIVED",
  "REFUND_ISSUED",
  "RENEWAL_INVOICE",
  "INVOICE_OVERDUE",
  "PLAN_CHANGED",
  "PLAN_LIMIT_WARNING",
]);
const PATH = "/notifications/preferences";

export function NotificationPreferences() {
  const queryClient = useQueryClient();
  const billing = usePermission("SUBSCRIPTION_MANAGE");
  const { data } = useQuery({
    queryKey: [PATH],
    queryFn: () => api.get<NotificationPreference[]>(PATH),
  });
  const update = useMutation({
    mutationFn: (preference: NotificationPreference) =>
      api.put(PATH, preference),
    onSettled: () => queryClient.invalidateQueries({ queryKey: [PATH] }),
  });
  return (
    <section className="space-y-3">
      <h2 className="text-base font-semibold text-[#1b2a55]">Email me when</h2>
      <p className="text-sm text-muted-foreground">
        These always appear under Notifications in the app.
      </p>
      <FormError message={update.error?.message} />
      {data?.filter(({ type }) => billing || !BILLING.has(type)).map(({ type, emailEnabled }) => (
        <div key={type} className="flex items-center gap-2">
          <Checkbox
            id={`email-${type}`}
            checked={emailEnabled}
            disabled={update.isPending}
            onCheckedChange={(checked) =>
              update.mutate({ type, emailEnabled: checked === true })
            }
          />
          <Label htmlFor={`email-${type}`} className="font-normal">
            {LABELS[type] ?? type}
          </Label>
        </div>
      ))}
    </section>
  );
}
