"use client";

import { useQuery } from "@tanstack/react-query";
import { createColumnHelper } from "@tanstack/react-table";
import { useState } from "react";
import { DataTable } from "@/components/records/data-table";
import { PageHeader, StatusBadge, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api, type PageResponse } from "@/lib/api";
import { formatQuantity, type FieldDef } from "@/lib/record-form";
import { parseSerials, toDispatchItems } from "@/lib/traceability";
import type { Customer, Dispatch, ProductionBatch } from "@/lib/types";

const RELEASED = "/production-batches?status=RELEASED&size=100";

function Row({ dispatch }: { dispatch: Dispatch }) {
  const canManage = usePermission("TRACEABILITY_MANAGE");
  const refresh = useRefresh();
  const [reversing, setReversing] = useState(false);
  const [returning, setReturning] = useState(false);
  const [shipping, setShipping] = useState(false);
  if (dispatch.reversalReason) return <StatusBadge status="REVERSED" />;
  if (!canManage) return null;
  const hasReturns = dispatch.lines.some((line) => Number(line.returnedQuantity) > 0);
  const shipped = dispatch.shippedOn !== null;
  const returnable = shipped
    ? dispatch.lines.filter((line) => Number(line.returnedQuantity) < Number(line.quantity))
    : [];
  const shipmentFields: FieldDef[] = [
    { name: "shippedOn", label: "Shipped on", type: "date", required: true },
    { name: "carrierReference", label: "Carrier or vehicle reference" },
  ];
  const returnFields: FieldDef[] = [
    {
      name: "dispatchItemId",
      label: "Line",
      type: "select",
      required: true,
      options: returnable.map((line) => ({
        value: line.id,
        label: `${line.batchNumber} (${formatQuantity(line.quantity)} shipped, ${formatQuantity(line.returnedQuantity)} returned)`,
      })),
    },
    { name: "quantity", label: "Returned quantity", type: "decimal", required: true },
    { name: "serials", label: "Serial numbers, if tracked (one per line)", type: "textarea" },
    { name: "returnReference", label: "Return reference", required: true },
    { name: "reversalDate", label: "Date received", type: "date", required: true },
    { name: "reason", label: "Reason for return", type: "textarea", required: true },
  ];
  return (
    <>
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
          onClose={() => setReturning(false)}
          onSubmit={({ dispatchItemId, quantity, serials, ...values }) =>
            api
              .post(`/dispatches/${dispatch.id}/reversals`, {
                ...values,
                items: [{ dispatchItemId, quantity, serialNumbers: parseSerials(String(serials ?? "")) }],
              })
              .then(refresh)
          }
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
    queryFn: () => api.get<PageResponse<ProductionBatch>>(RELEASED),
  });
  const [lines, setLines] = useState(1);
  const options = (batches?.items ?? [])
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
          })
          .then(refresh)
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
