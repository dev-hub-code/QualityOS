"use client";

import { useQuery } from "@tanstack/react-query";
import { createColumnHelper } from "@tanstack/react-table";
import { useState } from "react";
import { DataTable } from "@/components/records/data-table";
import { PageHeader, StatusBadge, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api, ApiError } from "@/lib/api";
import { formatQuantity, type FieldDef } from "@/lib/record-form";
import { toDispatchItems, toReturnItems } from "@/lib/traceability";
import type { Customer, Dispatch, ProductionBatch, ReversalSummary } from "@/lib/types";
import { lookupAll } from "@/components/records/parts";

const RELEASED = "/production-batches?status=RELEASED";

function Row({ dispatch }: { dispatch: Dispatch }) {
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
      const upTo = formatQuantity(String(Number(left.toFixed(6))));
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
          onClose={() => setShipping(false)}
          onSubmit={(values) => api.post(`/dispatches/${dispatch.id}/shipment`, values).then(refresh)}
        />
      )}
      {returning && (
        <RecordDialog
          title={`Record return against ${dispatch.dispatchNumber}`}
          fields={returnFields}
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
    `${formatQuantity(data.dispatchedQuantity)} dispatched`,
    Number(data.reversedQuantity) > 0 && `${formatQuantity(data.reversedQuantity)} back`,
    Number(data.pendingQuantity) > 0 && `${formatQuantity(data.pendingQuantity)} on its way back`,
    `${formatQuantity(data.remainingQuantity)} still with the customer`,
  ].filter(Boolean);
  return <p className="text-sm text-muted-foreground">{parts.join(" · ")}.</p>;
}

const column = createColumnHelper<Dispatch>();
const columns = [
  column.accessor("dispatchNumber", { header: "Number" }),
  column.accessor("customerName", { header: "Customer" }),
  column.accessor("dispatchedOn", { header: "Date" }),
  column.accessor("lines", {
    header: "Shipped",
    enableSorting: false,
    cell: (info) =>
      info
        .getValue()
        .map((line) => {
          const returned = Number(line.returnedQuantity) > 0 ? ` (${formatQuantity(line.returnedQuantity)} returned)` : "";
          return `${line.batchNumber} × ${formatQuantity(line.quantity)}${returned}`;
        })
        .join(", "),
  }),
  column.display({ id: "correction", header: "", cell: (info) => <Row dispatch={info.row.original} /> }),
];

function DispatchDialog({ onClose }: { onClose: () => void }) {
  const refresh = useRefresh();
  const { data: customers = [] } = useQuery({
    queryKey: ["/customers"],
    queryFn: () => api.get<Customer[]>("/customers"),
  });
  const { data: batches } = useQuery({
    queryKey: [RELEASED],
    queryFn: () => lookupAll<ProductionBatch>("/production-batches", "&status=RELEASED"),
  });
  const [lines, setLines] = useState(1);
  const canOverrideRecall = usePermission("RECALL_MANAGE");
  // Set once the server refuses goods under an open recall, so that someone allowed to may give a reason.
  const [heldByRecall, setHeldByRecall] = useState(false);
  const options = (batches ?? [])
    .filter((batch) => Number(batch.availableQuantity) > 0)
    .map((batch) => ({
      value: batch.id,
      label: `${batch.batchNumber} – ${batch.productCode} (${formatQuantity(batch.availableQuantity)} available)`,
    }));
  const fields: FieldDef[] = [
    {
      name: "customerId",
      label: "Customer",
      type: "select",
      required: true,
      options: customers.map((customer) => ({ value: customer.id, label: `${customer.code} – ${customer.name}` })),
    },
    { name: "dispatchedOn", label: "Date", type: "date", required: true },
    { name: "reference", label: "Invoice or vehicle reference" },
    ...Array.from({ length: lines }, (_, index): FieldDef[] => {
      const line = index + 1;
      return [
        { name: `productionBatchId${line}`, label: `Released batch ${line}`, type: "select", required: true, options },
        { name: `quantity${line}`, label: `Quantity ${line}`, type: "decimal", required: true },
        { name: `serials${line}`, label: `Serial numbers ${line}, if tracked (one per line)`, type: "textarea" },
      ];
    }).flat(),
    ...(heldByRecall && canOverrideRecall
      ? [{ name: "recallOverrideReason", label: "Reason to dispatch despite the recall", type: "textarea", required: true } as FieldDef]
      : []),
  ];
  return (
    <RecordDialog
      title="Record dispatch"
      fields={fields}
      onClose={onClose}
      extra={
        <Button type="button" variant="outline" onClick={() => setLines(lines + 1)}>
          Add another batch
        </Button>
      }
      onSubmit={(values) =>
        api
          .post("/dispatches", {
            customerId: values.customerId,
            dispatchedOn: values.dispatchedOn,
            reference: values.reference,
            items: toDispatchItems(values, lines),
            recallOverrideReason: values.recallOverrideReason || null,
          })
          .then(refresh)
          .catch((error: unknown) => {
            if (error instanceof ApiError && error.code === "RECALL_HOLD") setHeldByRecall(true);
            throw error;
          })
      }
    />
  );
}

export default function DispatchesPage() {
  const canManage = usePermission("TRACEABILITY_MANAGE");
  const [adding, setAdding] = useState(false);
  return (
    <div className="space-y-6">
      <PageHeader title="Dispatches">
        {canManage && <Button onClick={() => setAdding(true)}>Record dispatch</Button>}
      </PageHeader>
      <DataTable path="/dispatches" columns={columns} searchLabel="Search dispatches" emptyMessage="Nothing shipped yet." />
      {adding && <DispatchDialog onClose={() => setAdding(false)} />}
    </div>
  );
}
