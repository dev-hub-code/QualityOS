"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";
import { FormError } from "@/components/forms/field";
import { workOrderFields } from "@/components/manufacturing/work-order-fields";
import { RescheduleDialog } from "@/components/manufacturing/reschedule-dialog";
import { WorkOrderCompletionPanel } from "@/components/manufacturing/work-order-completion";
import { WorkOrderCostPanel } from "@/components/manufacturing/work-order-cost";
import { WorkOrderMaterialsPanel } from "@/components/manufacturing/work-order-materials";
import { WorkOrderQualityPanel } from "@/components/manufacturing/work-order-quality";
import {
  Details,
  PageHeader,
  QueryState,
  RecordLink,
  Section,
  StatusBadge,
  useAction,
  useFacilities,
  useProducts,
  useRefresh,
  useUnits,
} from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { useOwnApprovalRefused } from "@/hooks/use-own-approval";
import { PRIORITY_LABELS, describeLimits, reschedulable, workOrderActions } from "@/lib/manufacturing";
import { formatQuantity } from "@/lib/record-form";
import type { WorkOrderDetail, WorkOrderStatus } from "@/lib/types";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { formatDateTime } from "@/lib/dates";

/** The statuses whose reason is shown above the details. */
const REASON_LABELS: Partial<Record<WorkOrderStatus, string>> = {
  DRAFT: "Sent back",
  ON_HOLD: "On hold",
  CANCELLED: "Cancelled",
  PARTIALLY_COMPLETED: "Closed short",
};

const when = (value: string | null) => (value ? formatDateTime(value) : "–");
const link = "text-primary hover:underline";

type Dialog = "edit" | "sendBack" | "approve" | "hold" | "cancel" | "reschedule" | null;

export default function WorkOrderPage() {
  const confirm = useConfirm();
  const { id } = useParams<{ id: string }>();
  const path = `/manufacturing/work-orders/${id}`;
  const can = {
    manage: usePermission("WORK_ORDER_MANAGE"),
    approve: usePermission("WORK_ORDER_APPROVE"),
    release: usePermission("WORK_ORDER_RELEASE"),
  };
  const canSeeCost = usePermission("PRODUCTION_COST_VIEW");
  const products = useProducts();
  const facilities = useFacilities();
  const units = useUnits();
  const refresh = useRefresh();
  const { run, pending, message } = useAction();
  const [dialog, setDialog] = useState<Dialog>(null);
  const [warnings, setWarnings] = useState<string[] | null>(null);
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<WorkOrderDetail>(path) });
  const ownRefused = useOwnApprovalRefused(data?.workOrder.submittedBy ?? null);
  if (!data) return <QueryState error={error} />;

  const { workOrder: order, definition, materials, operations, history } = data;
  const actions = workOrderActions(order, can).filter((action) => action !== "approve" || !ownRefused);
  const unit = units.label(order.uomId);
  const close = () => setDialog(null);
  const released = order.releasedAt !== null;

  return (
    <div className="space-y-6">
      <PageHeader
        title={order.workOrderNumber}
        subtitle={
          <span className="flex flex-wrap items-center gap-2">
            <StatusBadge status={order.status} />
            {formatQuantity(order.quantity)} {unit} of {order.productCode} – {order.productName}
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
                title: "Submit this work order for approval?",
                description: "Approvers are notified, and the order can no longer be edited unless it is sent back.",
                confirmLabel: "Submit",
              }).then((ok) => {
                if (ok) void run(() => api.post(`${path}/submit`));
              })
            }>
            Submit for approval
          </Button>
        )}
        {actions.includes("approve") && <Button onClick={() => setDialog("approve")}>Approve</Button>}
        {actions.includes("sendBack") && (
          <Button variant="outline" onClick={() => setDialog("sendBack")}>
            Send back
          </Button>
        )}
        {actions.includes("release") && (
          <Button disabled={pending} onClick={() =>
              confirm({
                title: "Release this work order?",
                description: "The BOM or recipe and routing in effect are frozen onto the order, and material can be reserved for it. This cannot be undone.",
                confirmLabel: "Release",
              }).then((ok) => {
                if (ok) void run(() => api.post(`${path}/release`));
              })
            }>
            Release
          </Button>
        )}
        {actions.includes("start") && (
          <Button disabled={pending} onClick={() =>
              confirm({
                title: "Start production?",
                description: "The production batch opens, and the first operation becomes ready on the shop floor.",
                confirmLabel: "Start production",
              }).then((ok) => {
                if (ok) void run(() => api.post(`${path}/start`));
              })
            }>
            Start production
          </Button>
        )}
        {actions.includes("resume") && (
          <Button disabled={pending} onClick={() => run(() => api.post(`${path}/resume`))}>
            Resume
          </Button>
        )}
        {can.manage && reschedulable(order.status) && (
          <Button variant="outline" onClick={() => setDialog("reschedule")}>
            Reschedule
          </Button>
        )}
        {actions.includes("hold") && (
          <Button variant="outline" onClick={() => setDialog("hold")}>
            Put on hold
          </Button>
        )}
        {actions.includes("cancel") && (
          <Button variant="destructive" onClick={() => setDialog("cancel")}>
            Cancel order
          </Button>
        )}
      </PageHeader>
      {order.status === "SUBMITTED" && can.approve && ownRefused && (
        <p className="text-sm text-muted-foreground">You submitted this work order, so someone else must approve it.</p>
      )}
      <FormError message={message} />
      {warnings && (
        <div role="status" className="rounded-xl bg-amber-50 px-3 py-2 text-sm text-amber-900 ring-1 ring-amber-200">
          {warnings.length === 0 ? (
            <p>Rescheduled. It fits within capacity.</p>
          ) : (
            <>
              <p className="font-medium">Rescheduled, with warnings:</p>
              <ul className="mt-1 list-disc pl-5">
                {warnings.map((warning) => (
                  <li key={warning}>{warning}</li>
                ))}
              </ul>
            </>
          )}
        </div>
      )}
      {order.statusReason && REASON_LABELS[order.status] && (
        <p className="rounded-xl bg-amber-50 px-3 py-2 text-sm text-amber-900 ring-1 ring-amber-200">
          {REASON_LABELS[order.status]}: {order.statusReason}
        </p>
      )}
      <Section title="Details">
        <Details
          items={[
            ["Facility", order.facilityCode],
            ["Planned", `${order.plannedStart} to ${order.plannedEnd}`],
            ["Priority", PRIORITY_LABELS[order.priority]],
            [
              "Plan",
              order.planId ? (
                <Link key="plan" className={link} href={`/manufacturing/production-plans/${order.planId}`}>
                  {order.planNumber}
                </Link>
              ) : (
                "–"
              ),
            ],
            [
              "Production batch",
              order.productionBatchId ? (
                <span key="batch" className="flex flex-wrap gap-x-3">
                  <Link className={link} href={`/production-batches/${order.productionBatchId}`}>
                    {order.productionBatchNumber}
                  </Link>
                  <a className={link} href={`/api/v1/manufacturing/work-orders/${order.id}/production-record.pdf`}>
                    Production record (PDF)
                  </a>
                </span>
              ) : (
                "Opened when production starts"
              ),
            ],
            ["Submitted", order.submittedAt ? `${order.submittedByName ?? "Former member"}, ${when(order.submittedAt)}` : "–"],
            ["Approved", order.approvedAt ? `${order.approvedByName ?? "Former member"}, ${when(order.approvedAt)}` : "–"],
            ["Released", order.releasedAt ? `${order.releasedByName ?? "Former member"}, ${when(order.releasedAt)}` : "–"],
            ["Notes", order.notes ?? "–"],
          ]}
        />
      </Section>
      <Section title={released ? "Made from" : "Will be made from"}>
        <Details
          items={[
            [
              definition.definitionKind === "RECIPE" ? "Recipe" : definition.definitionKind === "BOM" ? "BOM" : "BOM or recipe",
              definition.definitionId ? (
                <Link
                  key="definition"
                  className={link}
                  href={`/manufacturing/${definition.definitionKind === "RECIPE" ? "recipes" : "boms"}/${definition.definitionId}`}
                >
                  Revision {definition.definitionRevision}
                </Link>
              ) : (
                "None in effect"
              ),
            ],
            [
              "Routing",
              definition.routingId ? (
                <Link key="routing" className={link} href={`/manufacturing/routings/${definition.routingId}`}>
                  Revision {definition.routingRevision}
                </Link>
              ) : (
                "None in effect"
              ),
            ],
          ]}
        />
        {definition.processParameters.length > 0 && (
          <div className="mt-4">
            <h3 className="mb-1 text-sm font-medium">Process conditions</h3>
            <ul className="space-y-0.5 text-sm">
              {definition.processParameters.map((parameter) => (
                <li key={parameter.id}>
                  {parameter.code} – {parameter.name}: {describeLimits(parameter)}
                </li>
              ))}
            </ul>
          </div>
        )}
        {!released && (
          <p className="mt-3 text-sm text-muted-foreground">
            Release uses the revisions in effect on the planned start and freezes them for this order.
          </p>
        )}
        {definition.problems.length > 0 && (
          <ul className="mt-3 space-y-1 rounded-xl bg-amber-50 px-3 py-2 text-sm text-amber-900 ring-1 ring-amber-200">
            {definition.problems.map((problem) => (
              <li key={problem}>{problem}</li>
            ))}
          </ul>
        )}
      </Section>
      {released &&
        (materials.length === 0 ? (
          <Section title="Materials">
            <p className="text-sm text-muted-foreground">No BOM or recipe was in effect, so nothing was worked out.</p>
          </Section>
        ) : (
          <WorkOrderMaterialsPanel orderId={order.id} status={order.status} />
        ))}
      {released && operations.length > 0 && (
        <Section title="Operations">
          <ul className="divide-y text-sm">
            {operations.map((operation) => (
              <li key={operation.id} className="flex flex-wrap items-center justify-between gap-3 py-2">
                <span>
                  <RecordLink href={`/manufacturing/shop-floor/${operation.id}`}>
                    {operation.sequence}. {operation.name}
                  </RecordLink>{" "}
                  at {operation.workCenterCode}
                  {(operation.operatorName || operation.machineCode) && (
                    <span className="text-muted-foreground">
                      {" "}
                      · {[operation.operatorName, operation.machineCode].filter(Boolean).join(" on ")}
                    </span>
                  )}
                  {operation.statusReason && <span className="block text-muted-foreground">{operation.statusReason}</span>}
                </span>
                <span className="flex flex-wrap items-center gap-2 text-muted-foreground">
                  {order.startedAt ? (
                    <>
                      Good {formatQuantity(operation.goodQuantity)} · rejected {formatQuantity(operation.rejectedQuantity)} · scrap{" "}
                      {formatQuantity(operation.scrapQuantity)} · waiting {formatQuantity(operation.waiting)} {unit}
                    </>
                  ) : (
                    <>
                      Setup {formatQuantity(operation.plannedSetupMinutes)} min · run {formatQuantity(operation.plannedRunMinutes)} min
                    </>
                  )}
                  <StatusBadge status={operation.status} />
                </span>
              </li>
            ))}
          </ul>
        </Section>
      )}
      {released && <WorkOrderQualityPanel orderId={order.id} status={order.status} operations={operations} />}
      {order.productionBatchId && <WorkOrderCompletionPanel orderId={order.id} status={order.status} uomId={order.uomId} />}
      {released && canSeeCost && <WorkOrderCostPanel orderId={order.id} />}
      <Section title="Status history">
        <ul className="divide-y text-sm">
          {history.map((change) => (
            <li key={`${change.changedAt}-${change.toStatus}`} className="flex flex-wrap items-center justify-between gap-3 py-2">
              <span className="flex items-center gap-2">
                <StatusBadge status={change.toStatus} />
                {change.reason}
              </span>
              <span className="text-muted-foreground">
                {change.changedByName ?? "Former member"} · {when(change.changedAt)}
              </span>
            </li>
          ))}
        </ul>
      </Section>
      {dialog === "edit" && (
        <RecordDialog
          title="Edit work order"
          fields={workOrderFields(products, facilities, order.planLineId !== null)}
          record={order}
          onClose={close}
          onSubmit={(values) =>
            api
              .put(path, { productId: order.productId, facilityId: order.facilityId, ...values, planLineId: order.planLineId })
              .then(refresh)
          }
        />
      )}
      {dialog === "reschedule" && <RescheduleDialog order={order} onClose={close} onDone={setWarnings} />}
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
          title={`Approve ${order.workOrderNumber}`}
          fields={[{ name: "note", label: "Note", type: "textarea" }]}
          onClose={close}
          onSubmit={(values) => api.post(`${path}/approve`, values).then(refresh)}
        />
      )}
      {dialog === "hold" && (
        <RecordDialog
          title="Put on hold"
          fields={[{ name: "reason", label: "Why it is on hold", type: "textarea", required: true }]}
          onClose={close}
          onSubmit={(values) => api.post(`${path}/hold`, values).then(refresh)}
        />
      )}
      {dialog === "cancel" && (
        <RecordDialog
          title={`Cancel ${order.workOrderNumber}`}
          fields={[{ name: "reason", label: "Why it is cancelled", type: "textarea", required: true }]}
          onClose={close}
          onSubmit={(values) => api.post(`${path}/cancel`, values).then(refresh)}
        />
      )}
    </div>
  );
}
