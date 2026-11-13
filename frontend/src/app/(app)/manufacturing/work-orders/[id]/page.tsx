"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";
import { FormError } from "@/components/forms/field";
import { workOrderFields } from "@/components/manufacturing/work-order-fields";
import {
  Details,
  PageHeader,
  QueryState,
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
import { PRIORITY_LABELS, workOrderActions } from "@/lib/manufacturing";
import { formatQuantity } from "@/lib/record-form";
import type { WorkOrderDetail } from "@/lib/types";

const when = (value: string | null) => (value ? new Date(value).toLocaleString() : "–");
const link = "text-primary hover:underline";

type Dialog = "edit" | "sendBack" | "approve" | "hold" | "cancel" | null;

export default function WorkOrderPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/manufacturing/work-orders/${id}`;
  const can = {
    manage: usePermission("WORK_ORDER_MANAGE"),
    approve: usePermission("WORK_ORDER_APPROVE"),
    release: usePermission("WORK_ORDER_RELEASE"),
  };
  const products = useProducts();
  const facilities = useFacilities();
  const units = useUnits();
  const refresh = useRefresh();
  const { run, pending, message } = useAction();
  const [dialog, setDialog] = useState<Dialog>(null);
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<WorkOrderDetail>(path) });
  if (!data) return <QueryState error={error} />;

  const { workOrder: order, definition, materials, operations, history } = data;
  const actions = workOrderActions(order, can);
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
          <Button disabled={pending} onClick={() => run(() => api.post(`${path}/submit`))}>
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
          <Button disabled={pending} onClick={() => run(() => api.post(`${path}/release`))}>
            Release
          </Button>
        )}
        {actions.includes("start") && (
          <Button disabled={pending} onClick={() => run(() => api.post(`${path}/start`))}>
            Start production
          </Button>
        )}
        {actions.includes("resume") && (
          <Button disabled={pending} onClick={() => run(() => api.post(`${path}/resume`))}>
            Resume
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
      <FormError message={message} />
      {order.statusReason && (order.status === "DRAFT" || order.status === "ON_HOLD" || order.status === "CANCELLED") && (
        <p className="rounded-xl bg-amber-50 px-3 py-2 text-sm text-amber-900 ring-1 ring-amber-200">
          {order.status === "DRAFT" ? "Sent back" : order.status === "ON_HOLD" ? "On hold" : "Cancelled"}: {order.statusReason}
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
                <Link key="batch" className={link} href={`/production-batches/${order.productionBatchId}`}>
                  {order.productionBatchNumber}
                </Link>
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
              definition.definitionKind === "RECIPE" ? "Recipe" : "BOM",
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
      {released && (
        <Section title="Materials needed">
          {materials.length === 0 ? (
            <p className="text-sm text-muted-foreground">No BOM or recipe was in effect, so nothing was worked out.</p>
          ) : (
            <table className="w-full text-sm">
              <thead className="text-left text-muted-foreground">
                <tr>
                  <th className="py-1 font-medium">Component</th>
                  <th className="py-1 text-right font-medium">Needed, scrap included</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {materials.map((row) => (
                  <tr key={row.lineNumber}>
                    <td className="py-2">
                      {row.componentCode} – {row.componentName}
                      {row.componentType === "PRODUCT" && <span className="text-muted-foreground"> (sub-assembly)</span>}
                    </td>
                    <td className="py-2 text-right">
                      {formatQuantity(row.requiredQuantity)} {units.label(row.uomId)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Section>
      )}
      {released && operations.length > 0 && (
        <Section title="Operations">
          <ul className="divide-y text-sm">
            {operations.map((operation) => (
              <li key={operation.id} className="flex flex-wrap items-center justify-between gap-3 py-2">
                <span>
                  {operation.sequence}. {operation.name} at {operation.workCenterCode}
                </span>
                <span className="flex items-center gap-2 text-muted-foreground">
                  Setup {formatQuantity(operation.plannedSetupMinutes)} min · run {formatQuantity(operation.plannedRunMinutes)} min
                  <StatusBadge status={operation.status} />
                </span>
              </li>
            ))}
          </ul>
        </Section>
      )}
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
          fields={[{ name: "note", label: "Note (needed if you are approving your own work order)", type: "textarea" }]}
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
