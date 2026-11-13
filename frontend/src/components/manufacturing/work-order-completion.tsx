"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Section, StatusBadge, useRefresh, useUnits } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { completionActions, newRequestKey } from "@/lib/manufacturing";
import type { FieldDef } from "@/lib/record-form";
import { formatQuantity } from "@/lib/record-form";
import { parseSerials } from "@/lib/traceability";
import type { FinishedGoodsReceipt, MaterialVariance, WorkOrderCompletion, WorkOrderStatus } from "@/lib/types";

const when = (value: string) => new Date(value).toLocaleString();

function Blockers({ title, items }: { title: string; items: string[] }) {
  if (items.length === 0) return null;
  return (
    <div className="mb-3 rounded-xl bg-amber-50 px-3 py-2 text-sm text-amber-900 ring-1 ring-amber-200">
      <p className="font-medium">{title}</p>
      <ul className="mt-1 list-disc space-y-0.5 pl-5">
        {items.map((item) => (
          <li key={item}>{item}</li>
        ))}
      </ul>
    </div>
  );
}

/** Each material line: what the output needed against what was issued. */
function Variances({ lines, unit }: { lines: MaterialVariance[]; unit: (id: string) => string }) {
  if (lines.length === 0) return null;
  return (
    <ul className="divide-y text-sm">
      {lines.map((line) => (
        <li key={line.lineNumber} className="flex flex-wrap items-center justify-between gap-3 py-2">
          <span>
            {line.componentCode} – {line.componentName}
          </span>
          <span className={line.outside ? "font-medium text-red-700" : "text-muted-foreground"}>
            Needed {formatQuantity(line.expected)} · issued {formatQuantity(line.issued)} {unit(line.uomId)}
            {line.percent !== null && ` · ${Number(line.percent) > 0 ? "+" : ""}${Number(line.percent)}%`}
          </span>
        </li>
      ))}
    </ul>
  );
}

function Receipt({ receipt, unit }: { receipt: FinishedGoodsReceipt; unit: string }) {
  return (
    <div className="space-y-1 text-sm">
      <p>
        <span className="font-medium">
          Received {formatQuantity(receipt.quantity)} of {formatQuantity(receipt.orderedQuantity)} {unit}
        </span>
        <span className="text-muted-foreground">
          {" "}
          · {receipt.receivedByName ?? "Former member"}, {when(receipt.receivedAt)}
        </span>
      </p>
      <p className="text-muted-foreground">
        Rejected {formatQuantity(receipt.rejectedQuantity)} · scrapped {formatQuantity(receipt.scrapQuantity)} · reworked and accepted{" "}
        {formatQuantity(receipt.reworkedQuantity)}
        {receipt.serialCount > 0 && ` · ${receipt.serialCount} serial numbers`}
      </p>
      {receipt.shortReason && <p>Short: {receipt.shortReason}</p>}
      {receipt.varianceReason && <p>Material variance: {receipt.varianceReason}</p>}
    </div>
  );
}

/**
 * Finishing a work order: receiving its finished goods into its batch, which is the stock dispatches draw on, then
 * closing it once its final inspection and any rework are settled.
 */
export function WorkOrderCompletionPanel({
  orderId,
  status,
  uomId,
}: {
  orderId: string;
  status: WorkOrderStatus;
  uomId: string;
}) {
  const path = `/manufacturing/work-orders/${orderId}`;
  const canComplete = usePermission("WORK_ORDER_COMPLETE");
  const units = useUnits();
  const refresh = useRefresh();
  const [dialog, setDialog] = useState<{ kind: "receive"; key: string } | { kind: "close" } | null>(null);
  const { data } = useQuery({
    queryKey: [`${path}/completion`],
    queryFn: () => api.get<WorkOrderCompletion>(`${path}/completion`),
  });
  if (!data) return null;

  const unit = units.label(uomId);
  const actions = completionActions(data, status, canComplete);
  const close = () => setDialog(null);
  const outside = data.materials.some((line) => line.outside);
  const mayBeShort = data.expectedQuantity === null || Number(data.expectedQuantity) < Number(data.orderedQuantity);
  const receiveFields: FieldDef[] = [
    ...(data.expectedQuantity === null ? [{ name: "quantity", label: `Quantity made (${unit})`, type: "decimal" as const, required: true }] : []),
    ...(mayBeShort ? [{ name: "shortReason", label: "Why it is short (needed when less was made than ordered)", type: "textarea" as const }] : []),
    ...(outside ? [{ name: "varianceReason", label: "Why material use was off", type: "textarea" as const, required: true }] : []),
    ...(data.serialsRequired
      ? [{ name: "serialNumbers", label: "Serial numbers, one per line", type: "textarea" as const, required: true }]
      : []),
  ];

  return (
    <>
      <Section
        title="Completion"
        action={
          <span className="flex flex-wrap gap-2">
            {actions.includes("receive") && (
              <Button size="sm" onClick={() => setDialog({ kind: "receive", key: newRequestKey() })}>
                Receive finished goods
              </Button>
            )}
            {actions.includes("close") && (
              <Button size="sm" onClick={() => setDialog({ kind: "close" })}>
                {data.closesAs === "PARTIALLY_COMPLETED" ? "Close short" : "Complete order"}
              </Button>
            )}
          </span>
        }
      >
        {data.completedAt && (
          <p className="mb-3 flex flex-wrap items-center gap-2 text-sm">
            <StatusBadge status={status} />
            {data.completedByName ?? "Former member"}, {when(data.completedAt)}
          </p>
        )}
        {status === "IN_PROGRESS" && <Blockers title="Receiving waits for" items={data.receiveBlockers} />}
        {status === "IN_PROGRESS" && <Blockers title="Closing waits for" items={data.closeBlockers} />}
        {data.receipt ? (
          <Receipt receipt={data.receipt} unit={unit} />
        ) : (
          <p className="text-sm text-muted-foreground">
            Ordered {formatQuantity(data.orderedQuantity)} {unit}
            {data.expectedQuantity !== null && ` · good so far ${formatQuantity(data.expectedQuantity)} ${unit}`} · rejected{" "}
            {formatQuantity(data.rejectedQuantity)} · scrapped {formatQuantity(data.scrapQuantity)}
          </p>
        )}
        {data.materials.length > 0 && (
          <>
            <h3 className="mt-4 mb-1 text-sm font-medium">{data.receipt ? "Material use at receipt" : "Material use so far"}</h3>
            <Variances lines={data.materials} unit={units.label} />
          </>
        )}
      </Section>
      {dialog?.kind === "receive" && (
        <RecordDialog
          title="Receive finished goods"
          fields={receiveFields}
          onClose={close}
          onSubmit={(values) => {
            const { serialNumbers, ...rest } = values;
            const body = serialNumbers ? { ...rest, serialNumbers: parseSerials(String(serialNumbers)) } : rest;
            return api.post(`${path}/receipt`, body, { "Idempotency-Key": dialog.key }).then(refresh);
          }}
        />
      )}
      {dialog?.kind === "close" && (
        <RecordDialog
          title={data.closesAs === "PARTIALLY_COMPLETED" ? "Close the order short" : "Complete the order"}
          fields={[
            {
              name: "reason",
              label: data.closesAs === "PARTIALLY_COMPLETED" ? "Why it closes short" : "Note",
              type: "textarea",
              required: data.closesAs === "PARTIALLY_COMPLETED",
            },
          ]}
          onClose={close}
          onSubmit={(values) => api.post(`${path}/complete`, values).then(refresh)}
        />
      )}
    </>
  );
}
