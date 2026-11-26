"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { RecordDialog } from "@/components/records/record-dialog";
import { StatusBadge, useRefresh } from "@/components/records/parts";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api, ApiError } from "@/lib/api";
import { formatQuantityIn, type FieldDef } from "@/lib/record-form";
import { toReturnItems } from "@/lib/traceability";
import { today } from "@/lib/dates";
import type { Dispatch, ReversalSummary } from "@/lib/types";

/** A dispatch's state, and what can be done with it: confirm the shipment, record a return, or reverse a mistake. */
export function DispatchActions({ dispatch }: { dispatch: Dispatch }) {
  const canManage = usePermission("TRACEABILITY_MANAGE");
  const refresh = useRefresh();
  const [reversing, setReversing] = useState(false);
  const [returning, setReturning] = useState(false);
  const [shipping, setShipping] = useState(false);
  if (dispatch.reversalReason) return <StatusBadge status="REVERSED" />;
  if (!canManage) return <StatusBadge status={dispatch.shippedOn ? "SHIPPED" : "AWAITING_SHIPMENT"} />;
  const hasReturns = dispatch.lines.some((line) => Number(line.returnedQuantity) > 0);
  const shipped = dispatch.shippedOn !== null;
  const returnable = shipped
    ? dispatch.lines.filter(
        (line) => Number(line.returnedQuantity) + Number(line.expectedQuantity) < Number(line.quantity),
      )
    : [];
  const shipmentFields: FieldDef[] = [
    { name: "shippedOn", label: "Shipped on", type: "date", required: true },
    { name: "carrierReference", label: "Carrier or vehicle reference" },
  ];
  // One quantity per line that can still come back. A batch shipped on two lines is told apart by its line number.
  const returnFields: FieldDef[] = [
    ...returnable.flatMap((line, index): FieldDef[] => {
      const twice = returnable.filter((other) => other.batchNumber === line.batchNumber).length > 1;
      const name = twice ? `${line.batchNumber}, line ${index + 1}` : line.batchNumber;
      const left = Number(line.quantity) - Number(line.returnedQuantity) - Number(line.expectedQuantity);
      const upTo = formatQuantityIn(String(Number(left.toFixed(6))), line.unit);
      return [
        { name: `quantity${index + 1}`, label: `Returned from ${name} (up to ${upTo})`, type: "decimal" },
        { name: `serials${index + 1}`, label: `Serial numbers from ${name}, if tracked (one per line)`, type: "textarea" },
      ];
    }),
    { name: "returnReference", label: "Return reference", required: true },
    { name: "reversalDate", label: "Date received or expected", type: "date", required: true },
    { name: "expected", label: "Not here yet: the goods are on their way", type: "checkbox" },
    { name: "reason", label: "Reason for return", type: "textarea", required: true },
    { name: "qualityComplaint", label: "Something was wrong with the goods: raise an NCR", type: "checkbox" },
  ];
  return (
    <>
      <StatusBadge status={shipped ? "SHIPPED" : "AWAITING_SHIPMENT"} />
      {returnable.length > 0 && (
        <Button variant="ghost" size="sm" onClick={() => setReturning(true)}>
          Record return
        </Button>
      )}
      {!shipped && (
        <Button variant="ghost" size="sm" onClick={() => setShipping(true)}>
          Confirm shipment
        </Button>
      )}
      {!shipped && !hasReturns && (
        <Button variant="ghost" size="sm" onClick={() => setReversing(true)}>
          Reverse
        </Button>
      )}
      {shipping && (
        <RecordDialog
          title={`Confirm shipment of ${dispatch.dispatchNumber}`}
          fields={shipmentFields}
          record={{ shippedOn: today() }}
          onClose={() => setShipping(false)}
          onSubmit={(values) => api.post(`/dispatches/${dispatch.id}/shipment`, values).then(refresh)}
        />
      )}
      {returning && (
        <RecordDialog
          title={`Record return against ${dispatch.dispatchNumber}`}
          fields={returnFields}
          record={{ reversalDate: today() }}
          extra={<ReturnStanding dispatchId={dispatch.id} />}
          onClose={() => setReturning(false)}
          onSubmit={async (values) => {
            const items = toReturnItems(
              values,
              returnable.map((line) => line.id),
            );
            if (items.length === 0) {
              throw new ApiError(400, "VALIDATION_ERROR", "Enter the quantity returned from at least one batch.");
            }
            await api.post(`/dispatches/${dispatch.id}/reversals`, {
              returnReference: values.returnReference,
              reversalDate: values.reversalDate,
              expected: values.expected,
              reason: values.reason,
              qualityComplaint: values.qualityComplaint,
              items,
            });
            await refresh();
          }}
        />
      )}
      {reversing && (
        <RecordDialog
          title={`Reverse ${dispatch.dispatchNumber} as a mistake`}
          fields={[{ name: "reason", label: "Why this dispatch never really happened", type: "textarea", required: true }]}
          onClose={() => setReversing(false)}
          onSubmit={(values) => api.post(`/dispatches/${dispatch.id}/reverse`, values).then(refresh)}
        />
      )}
    </>
  );
}

/** How much of the dispatch is already back, expected back, or still with the customer. */
function ReturnStanding({ dispatchId }: { dispatchId: string }) {
  const path = `/dispatches/${dispatchId}/reversal-summary`;
  const { data } = useQuery({ queryKey: [path], queryFn: () => api.get<ReversalSummary>(path) });
  if (!data) return null;
  const parts = [
    `${data.labels.dispatched} dispatched`,
    Number(data.reversedQuantity) > 0 && `${data.labels.reversed} back`,
    Number(data.pendingQuantity) > 0 && `${data.labels.pending} on its way back`,
    `${data.labels.remaining} still with the customer`,
  ].filter(Boolean);
  return <p className="text-sm text-muted-foreground">{parts.join(" · ")}.</p>;
}
