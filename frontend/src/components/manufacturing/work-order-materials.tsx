"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { FormError } from "@/components/forms/field";
import { Section, StatusBadge, useAction, useRefresh, useUnits } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { newRequestKey } from "@/lib/manufacturing";
import { formatQuantity } from "@/lib/record-form";
import type { WorkOrderMaterials, WorkOrderStatus } from "@/lib/types";

type Line = WorkOrderMaterials["lines"][number];
type Consumption = WorkOrderMaterials["consumptions"][number];
type Dialog =
  | { kind: "reserve" | "issue"; line: Line; key: string }
  | { kind: "return"; consumption: Consumption; key: string }
  | null;

const cell = "py-2 text-right tabular-nums";

/**
 * What a released order needs, what is set aside and issued, and what is short. Every stock movement goes through the
 * server, which locks the lot and refuses what does not fit; each dialog sends one key, so a double click moves stock
 * once.
 */
export function WorkOrderMaterialsPanel({ orderId, status }: { orderId: string; status: WorkOrderStatus }) {
  const path = `/manufacturing/work-orders/${orderId}`;
  const canReserve = usePermission("WORK_ORDER_MATERIAL_RESERVE");
  const canIssue = usePermission("WORK_ORDER_MATERIAL_ISSUE");
  const units = useUnits();
  const refresh = useRefresh();
  const { run, pending, message } = useAction();
  const [dialog, setDialog] = useState<Dialog>(null);
  const { data } = useQuery({
    queryKey: [`${path}/materials`],
    queryFn: () => api.get<WorkOrderMaterials>(`${path}/materials`),
  });
  if (!data || data.lines.length === 0) return null;

  const reservable = canReserve && (status === "RELEASED" || status === "IN_PROGRESS");
  const issuable = canIssue && status === "IN_PROGRESS";
  const amount = (value: string, uomId: string) => `${formatQuantity(value)} ${units.label(uomId)}`;
  const close = () => setDialog(null);

  return (
    <>
      <Section
        title="Materials"
        action={
          reservable && (
            <Button size="sm" variant="outline" disabled={pending} onClick={() => run(() => api.post(`${path}/reservations/auto`, {}, { "Idempotency-Key": newRequestKey() }))}>
              Reserve what is free
            </Button>
          )
        }
      >
        <FormError message={message} />
        {data.shortages.length > 0 && (
          <ul className="mb-3 space-y-1 rounded-xl bg-amber-50 px-3 py-2 text-sm text-amber-900 ring-1 ring-amber-200">
            {data.shortages.map((shortage) => (
              <li key={shortage}>{shortage}</li>
            ))}
          </ul>
        )}
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="text-left text-muted-foreground">
              <tr>
                <th className="py-1 font-medium">Component</th>
                <th className="py-1 text-right font-medium">Needed</th>
                <th className="py-1 text-right font-medium">Issued</th>
                <th className="py-1 text-right font-medium">Reserved</th>
                <th className="py-1 text-right font-medium">Still to find</th>
                <th className="py-1 text-right font-medium">Free in facility</th>
                <th />
              </tr>
            </thead>
            <tbody className="divide-y">
              {data.lines.map((line) => (
                <tr key={line.lineNumber}>
                  <td className="py-2">
                    {line.lineNumber}. {line.componentCode} – {line.componentName}
                    {Number(line.shortage) > 0 && (
                      <span className="ml-2 text-amber-700">short {amount(line.shortage, line.uomId)}</span>
                    )}
                  </td>
                  <td className={cell}>{amount(line.required, line.uomId)}</td>
                  <td className={cell}>{amount(line.issued, line.uomId)}</td>
                  <td className={cell}>{line.componentType === "MATERIAL" ? amount(line.reserved, line.uomId) : "–"}</td>
                  <td className={cell}>{amount(line.outstanding, line.uomId)}</td>
                  <td className={cell}>{amount(line.available, line.uomId)}</td>
                  <td className="py-2 text-right whitespace-nowrap">
                    {reservable && line.componentType === "MATERIAL" && Number(line.outstanding) > 0 && line.sources.length > 0 && (
                      <Button size="sm" variant="ghost" onClick={() => setDialog({ kind: "reserve", line, key: newRequestKey() })}>
                        Reserve
                      </Button>
                    )}
                    {issuable && (line.sources.length > 0 || Number(line.reserved) > 0) && (
                      <Button size="sm" variant="outline" onClick={() => setDialog({ kind: "issue", line, key: newRequestKey() })}>
                        Issue
                      </Button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Section>
      {data.reservations.length > 0 && (
        <Section title="Reservations">
          <ul className="divide-y text-sm">
            {data.reservations.map((reservation) => (
              <li key={reservation.id} className="flex flex-wrap items-center justify-between gap-3 py-2">
                <span>
                  Line {reservation.lineNumber} · lot {reservation.lotNumber} · {amount(reservation.openQuantity, reservation.uomId)} held of{" "}
                  {amount(reservation.quantity, reservation.uomId)}
                </span>
                {canReserve && Number(reservation.openQuantity) > 0 && (
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={pending}
                    onClick={() => run(() => api.post(`${path}/reservations/${reservation.id}/release`, {}))}
                  >
                    Release
                  </Button>
                )}
              </li>
            ))}
          </ul>
        </Section>
      )}
      {data.consumptions.length > 0 && (
        <Section title="Issued to the batch">
          <ul className="divide-y text-sm">
            {data.consumptions.map((consumption) => (
              <li key={consumption.id} className="flex flex-wrap items-center justify-between gap-3 py-2">
                <span>
                  {consumption.componentCode} from {consumption.sourceNumber} · {amount(consumption.quantity, consumption.uomId)}
                  {consumption.lineNumber === null && <span className="ml-2 text-amber-700">not on the material list</span>}
                </span>
                {issuable && (
                  <Button size="sm" variant="ghost" onClick={() => setDialog({ kind: "return", consumption, key: newRequestKey() })}>
                    Return
                  </Button>
                )}
              </li>
            ))}
          </ul>
        </Section>
      )}
      {data.movements.length > 0 && (
        <Section title="Stock movements">
          <ul className="divide-y text-sm">
            {data.movements.map((movement) => (
              <li key={movement.id} className="flex flex-wrap items-center justify-between gap-3 py-2">
                <span className="flex items-center gap-2">
                  <StatusBadge status={movement.movementType} />
                  {amount(movement.quantity, movement.uomId)} · {movement.sourceNumber}
                  {movement.reason && <span className="text-muted-foreground">· {movement.reason}</span>}
                </span>
                <span className="text-muted-foreground">
                  {movement.movedByName ?? "Former member"} · {new Date(movement.movedAt).toLocaleString()}
                </span>
              </li>
            ))}
          </ul>
        </Section>
      )}
      {dialog?.kind === "reserve" && (
        <RecordDialog
          title={`Reserve ${dialog.line.componentCode}`}
          fields={[
            {
              name: "lotId",
              label: "Lot",
              type: "select",
              required: true,
              options: dialog.line.sources.map((source) => ({
                value: source.id,
                label: `${source.number} (${amount(source.freeQuantity, source.uomId)} free)`,
              })),
            },
            { name: "quantity", label: `Quantity, in ${units.label(dialog.line.uomId)}`, type: "decimal", required: true },
          ]}
          record={{ quantity: formatQuantity(dialog.line.outstanding) }}
          onClose={close}
          onSubmit={(values) =>
            api
              .post(`${path}/reservations`, { ...values, lineNumber: dialog.line.lineNumber }, { "Idempotency-Key": dialog.key })
              .then(refresh)
          }
        />
      )}
      {dialog?.kind === "issue" && (
        <RecordDialog
          title={`Issue ${dialog.line.componentCode}`}
          fields={[
            {
              name: "source",
              label: dialog.line.componentType === "MATERIAL" ? "Lot" : "Batch",
              type: "select",
              required: true,
              options: [
                ...data.reservations
                  .filter((reservation) => reservation.lineNumber === dialog.line.lineNumber && Number(reservation.openQuantity) > 0)
                  .map((reservation) => ({
                    value: reservation.lotId,
                    label: `${reservation.lotNumber} (${amount(reservation.openQuantity, reservation.uomId)} reserved)`,
                  })),
                ...dialog.line.sources
                  .filter((source) => !data.reservations.some((r) => r.lotId === source.id && Number(r.openQuantity) > 0))
                  .map((source) => ({ value: source.id, label: `${source.number} (${amount(source.freeQuantity, source.uomId)} free)` })),
              ],
            },
            { name: "quantity", label: `Quantity, in ${units.label(dialog.line.uomId)}`, type: "decimal", required: true },
          ]}
          record={{ quantity: formatQuantity(String(Math.max(Number(dialog.line.required) - Number(dialog.line.issued), 0))) }}
          onClose={close}
          onSubmit={(values) =>
            api
              .post(
                `${path}/issues`,
                {
                  lineNumber: dialog.line.lineNumber,
                  quantity: values.quantity,
                  lotId: dialog.line.componentType === "MATERIAL" ? values.source : null,
                  sourceBatchId: dialog.line.componentType === "PRODUCT" ? values.source : null,
                },
                { "Idempotency-Key": dialog.key },
              )
              .then(refresh)
          }
        />
      )}
      {dialog?.kind === "return" && (
        <RecordDialog
          title={`Return ${dialog.consumption.componentCode} to ${dialog.consumption.sourceNumber}`}
          fields={[
            { name: "quantity", label: `Quantity, in ${units.label(dialog.consumption.uomId)}`, type: "decimal", required: true },
            { name: "reason", label: "Why it goes back", type: "textarea", required: true },
          ]}
          onClose={close}
          onSubmit={(values) =>
            api
              .post(`${path}/returns`, { ...values, consumptionId: dialog.consumption.id }, { "Idempotency-Key": dialog.key })
              .then(refresh)
          }
        />
      )}
    </>
  );
}
