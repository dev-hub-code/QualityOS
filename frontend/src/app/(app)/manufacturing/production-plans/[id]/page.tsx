"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useState } from "react";
import { FormError } from "@/components/forms/field";
import { planFields, planLineFields, workOrderFields } from "@/components/manufacturing/work-order-fields";
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
import { planActions } from "@/lib/manufacturing";
import { formatQuantity } from "@/lib/record-form";
import type { PlanDetail, PlanLine, WorkOrderDetail } from "@/lib/types";

type Dialog = { kind: "edit" | "line" | "close" | "cancel" } | { kind: "editLine" | "order"; line: PlanLine } | null;

export default function ProductionPlanPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/manufacturing/production-plans/${id}`;
  const canManage = usePermission("PRODUCTION_PLAN_MANAGE");
  const canApprove = usePermission("PRODUCTION_PLAN_APPROVE");
  const canOrder = usePermission("WORK_ORDER_MANAGE");
  const products = useProducts();
  const facilities = useFacilities();
  const units = useUnits();
  const refresh = useRefresh();
  const router = useRouter();
  const { run, pending, message } = useAction();
  const [dialog, setDialog] = useState<Dialog>(null);
  const { data, error } = useQuery({ queryKey: [path], queryFn: () => api.get<PlanDetail>(path) });
  if (!data) return <QueryState error={error} />;

  const { plan, lines } = data;
  const actions = planActions(plan.status, { manage: canManage, approve: canApprove, order: canOrder });
  const editable = actions.includes("edit");
  const close = () => setDialog(null);

  return (
    <div className="space-y-6">
      <PageHeader
        title={`${plan.planNumber} – ${plan.name}`}
        subtitle={
          <span className="flex flex-wrap items-center gap-2">
            <StatusBadge status={plan.status} />
            {plan.facilityCode} · {plan.periodStart} to {plan.periodEnd}
          </span>
        }
      >
        {editable && (
          <Button variant="outline" onClick={() => setDialog({ kind: "edit" })}>
            Edit
          </Button>
        )}
        {actions.includes("approve") && (
          <Button disabled={pending} onClick={() => run(() => api.post(`${path}/approve`))}>
            Approve plan
          </Button>
        )}
        {actions.includes("close") && (
          <Button variant="outline" onClick={() => setDialog({ kind: "close" })}>
            Close plan
          </Button>
        )}
        {actions.includes("cancel") && (
          <Button variant="destructive" onClick={() => setDialog({ kind: "cancel" })}>
            Cancel plan
          </Button>
        )}
      </PageHeader>
      <FormError message={message} />
      <Section title="Details">
        <Details
          items={[
            ["Approved", plan.approvedAt ? `${plan.approvedByName ?? "Former member"}, ${new Date(plan.approvedAt).toLocaleString()}` : "–"],
            ["Notes", plan.notes ?? "–"],
            ...(plan.statusReason ? ([["Reason", plan.statusReason]] as [string, string][]) : []),
          ]}
        />
      </Section>
      <Section
        title="Lines"
        action={
          editable && (
            <Button size="sm" variant="outline" onClick={() => setDialog({ kind: "line" })}>
              Add line
            </Button>
          )
        }
      >
        {lines.length === 0 ? (
          <p className="text-sm text-muted-foreground">No lines yet. Add what this plan means to make.</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-left text-muted-foreground">
                <tr>
                  <th className="py-1 font-medium">Product</th>
                  <th className="py-1 font-medium">Due</th>
                  <th className="py-1 text-right font-medium">Planned</th>
                  <th className="py-1 text-right font-medium">Ordered</th>
                  <th className="py-1 font-medium">Work orders</th>
                  <th />
                </tr>
              </thead>
              <tbody className="divide-y">
                {lines.map((line) => {
                  const unit = units.label(line.uomId);
                  return (
                    <tr key={line.id}>
                      <td className="py-2">
                        {line.productCode} – {line.productName}
                      </td>
                      <td className="py-2">{line.dueDate}</td>
                      <td className="py-2 text-right">
                        {formatQuantity(line.quantity)} {unit}
                      </td>
                      <td className="py-2 text-right">
                        {formatQuantity(line.orderedQuantity)} {unit}
                        {line.overOrdered && <span className="ml-2 text-amber-700">more than planned</span>}
                      </td>
                      <td className="py-2">
                        <span className="flex flex-wrap gap-2">
                          {line.workOrders.map((order) => (
                            <Link key={order.id} className="text-primary hover:underline" href={`/manufacturing/work-orders/${order.id}`}>
                              {order.workOrderNumber}
                              {order.status === "CANCELLED" && " (cancelled)"}
                            </Link>
                          ))}
                        </span>
                      </td>
                      <td className="py-2 text-right whitespace-nowrap">
                        {editable && (
                          <>
                            <Button size="sm" variant="ghost" onClick={() => setDialog({ kind: "editLine", line })}>
                              Edit
                            </Button>
                            <Button
                              size="sm"
                              variant="ghost"
                              disabled={pending}
                              onClick={() => run(() => api.delete(`${path}/lines/${line.id}`))}
                            >
                              Remove
                            </Button>
                          </>
                        )}
                        {actions.includes("order") && (
                          <Button size="sm" variant="outline" onClick={() => setDialog({ kind: "order", line })}>
                            Raise work order
                          </Button>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Section>
      {dialog?.kind === "edit" && (
        <RecordDialog
          title="Edit plan"
          fields={planFields(facilities)}
          record={plan}
          onClose={close}
          onSubmit={(values) => api.put(path, values).then(refresh)}
        />
      )}
      {dialog?.kind === "line" && (
        <RecordDialog
          title="Add line"
          fields={planLineFields(products)}
          onClose={close}
          onSubmit={(values) => api.post(`${path}/lines`, values).then(refresh)}
        />
      )}
      {dialog?.kind === "editLine" && (
        <RecordDialog
          title="Edit line"
          fields={planLineFields(products)}
          record={dialog.line}
          onClose={close}
          onSubmit={(values) => api.put(`${path}/lines/${dialog.line.id}`, values).then(refresh)}
        />
      )}
      {dialog?.kind === "order" && (
        <RecordDialog
          title={`Work order for ${dialog.line.productCode}`}
          fields={workOrderFields(products, facilities, true)}
          record={{
            quantity: Number(dialog.line.remainingQuantity) === 0 ? "" : formatQuantity(dialog.line.remainingQuantity),
            plannedEnd: dialog.line.dueDate,
            priority: "NORMAL",
          }}
          onClose={close}
          onSubmit={(values) =>
            api
              .post<WorkOrderDetail>("/manufacturing/work-orders", {
                ...values,
                productId: dialog.line.productId,
                facilityId: plan.facilityId,
                planLineId: dialog.line.id,
              })
              .then((created) => router.push(`/manufacturing/work-orders/${created.workOrder.id}`))
          }
        />
      )}
      {dialog?.kind === "close" && (
        <RecordDialog
          title="Close plan"
          fields={[{ name: "note", label: "Note", type: "textarea" }]}
          onClose={close}
          onSubmit={(values) => api.post(`${path}/close`, values).then(refresh)}
        />
      )}
      {dialog?.kind === "cancel" && (
        <RecordDialog
          title="Cancel plan"
          fields={[{ name: "reason", label: "Why it is cancelled", type: "textarea", required: true }]}
          onClose={close}
          onSubmit={(values) => api.post(`${path}/cancel`, values).then(refresh)}
        />
      )}
    </div>
  );
}
