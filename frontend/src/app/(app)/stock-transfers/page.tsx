"use client";

import { useQuery } from "@tanstack/react-query";
import { createColumnHelper } from "@tanstack/react-table";
import { useState } from "react";
import { DataTable } from "@/components/records/data-table";
import {
  lookupAll,
  PageHeader,
  RecordLink,
  StatusBadge,
  useFacilities,
  useRefresh,
} from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { formatQuantity, type FieldDef } from "@/lib/record-form";
import { toTransferItems } from "@/lib/traceability";
import type { ProductionBatch, ReleasedLot, StockTransfer } from "@/lib/types";
import { formatCalendarDay, today } from "@/lib/dates";

const LOTS = "/material-receipts/released-lots";

/** Where a line's stock went: the lot or batch it became at the other plant, once received. */
function Lines({ transfer }: { transfer: StockTransfer }) {
  return (
    <ul className="space-y-0.5">
      {transfer.items.map((line) => (
        <li key={line.sourceId}>
          <RecordLink
            href={`/traceability/${encodeURIComponent(line.sourceNumber)}`}
          >
            {line.sourceNumber}
          </RecordLink>{" "}
          × {formatQuantity(line.quantity)} ({line.itemCode})
          {line.receivedNumber && (
            <>
              {" → "}
              <RecordLink
                href={`/traceability/${encodeURIComponent(line.receivedNumber)}`}
              >
                {line.receivedNumber}
              </RecordLink>
            </>
          )}
        </li>
      ))}
    </ul>
  );
}

function Actions({ transfer }: { transfer: StockTransfer }) {
  const canManage = usePermission("TRACEABILITY_MANAGE");
  const refresh = useRefresh();
  const [receiving, setReceiving] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  if (transfer.status !== "IN_TRANSIT" || !canManage) return null;
  const path = `/stock-transfers/${transfer.id}`;
  return (
    <>
      <Button variant="ghost" size="sm" onClick={() => setReceiving(true)}>
        Receive
      </Button>
      <Button variant="ghost" size="sm" onClick={() => setCancelling(true)}>
        Cancel
      </Button>
      {receiving && (
        <RecordDialog
          title={`Receive ${transfer.transferNumber} at ${transfer.toFacilityName}`}
          fields={[
            {
              name: "receivedOn",
              label: "Received on",
              type: "date",
              required: true,
            },
          ]}
          record={{ receivedOn: today() }}
          onClose={() => setReceiving(false)}
          onSubmit={(values) =>
            api.post(`${path}/receive`, values).then(refresh)
          }
        />
      )}
      {cancelling && (
        <RecordDialog
          title={`Cancel ${transfer.transferNumber}`}
          fields={[
            {
              name: "reason",
              label: "Why: the stock goes back to " + transfer.fromFacilityName,
              type: "textarea",
              required: true,
            },
          ]}
          onClose={() => setCancelling(false)}
          onSubmit={(values) =>
            api.post(`${path}/cancel`, values).then(refresh)
          }
        />
      )}
    </>
  );
}

const column = createColumnHelper<StockTransfer>();
const columns = [
  column.accessor("transferNumber", { header: "Number" }),
  column.accessor("sentOn", { header: "Sent", cell: (info) => formatCalendarDay(info.getValue()) }),
  column.display({
    id: "route",
    header: "From → to",
    cell: (info) =>
      `${info.row.original.fromFacilityName} → ${info.row.original.toFacilityName}`,
  }),
  column.display({
    id: "lines",
    header: "Stock",
    cell: (info) => <Lines transfer={info.row.original} />,
  }),
  column.accessor("status", {
    header: "Status",
    cell: (info) => (
      <span className="flex flex-wrap items-center gap-1">
        <StatusBadge status={info.getValue()} />
        {info.row.original.receivedOn && (
          <span className="text-muted-foreground">
            {formatCalendarDay(info.row.original.receivedOn)}
          </span>
        )}
        {info.row.original.cancelReason && (
          <span className="text-muted-foreground">
            {info.row.original.cancelReason}
          </span>
        )}
      </span>
    ),
  }),
  column.display({
    id: "actions",
    header: "",
    cell: (info) => <Actions transfer={info.row.original} />,
  }),
];

function SendDialog({ onClose }: { onClose: () => void }) {
  const refresh = useRefresh();
  const facilities = useFacilities();
  const [lines, setLines] = useState(1);
  const { data: lots = [] } = useQuery({
    queryKey: [LOTS],
    queryFn: () => api.get<ReleasedLot[]>(LOTS),
  });
  const { data: batches = [] } = useQuery({
    queryKey: ["/production-batches", "released"],
    queryFn: () =>
      lookupAll<ProductionBatch>("/production-batches", "&status=RELEASED"),
  });
  const plant = (id: string) =>
    facilities.find((facility) => facility.value === id)?.label ?? "";
  // Each choice says which plant it is at: a transfer leaves from the plant chosen above.
  const stock = [
    ...lots
      .filter(
        (lot) =>
          Number(lot.remainingQuantity) - Number(lot.reservedQuantity) > 0,
      )
      .map((lot) => ({
        value: `LOT:${lot.itemId}`,
        label: `${lot.internalBatch} – ${lot.materialCode} (${formatQuantity(String(Number(lot.remainingQuantity) - Number(lot.reservedQuantity)))} free) at ${plant(lot.facilityId)}`,
      })),
    ...batches
      .filter((batch) => Number(batch.availableQuantity) > 0)
      .map((batch) => ({
        value: `BATCH:${batch.id}`,
        label: `${batch.batchNumber} – ${batch.productCode} (${formatQuantity(batch.availableQuantity)} available) at ${plant(batch.facilityId)}`,
      })),
  ];
  const fields: FieldDef[] = [
    {
      name: "fromFacilityId",
      label: "From plant",
      type: "select",
      required: true,
      options: facilities,
    },
    {
      name: "toFacilityId",
      label: "To plant",
      type: "select",
      required: true,
      options: facilities,
    },
    { name: "sentOn", label: "Sent on", type: "date", required: true },
    ...Array.from({ length: lines }, (_, index): FieldDef[] => [
      {
        name: `stock${index + 1}`,
        label: `Lot or batch ${index + 1}`,
        type: "select",
        required: true,
        options: stock,
      },
      {
        name: `quantity${index + 1}`,
        label: `Quantity ${index + 1}`,
        type: "decimal",
        required: true,
      },
    ]).flat(),
    { name: "note", label: "Vehicle, challan or other note", type: "textarea" },
  ];
  return (
    <RecordDialog
      title="Send stock to another plant"
      fields={fields}
      record={{ sentOn: today() }}
      onClose={onClose}
      extra={
        <Button
          type="button"
          variant="outline"
          onClick={() => setLines(lines + 1)}
        >
          Add another lot or batch
        </Button>
      }
      onSubmit={(values) =>
        api
          .post("/stock-transfers", {
            fromFacilityId: values.fromFacilityId,
            toFacilityId: values.toFacilityId,
            sentOn: values.sentOn,
            note: values.note || null,
            items: toTransferItems(values, lines),
          })
          .then(refresh)
      }
    />
  );
}

export default function StockTransfersPage() {
  const canManage = usePermission("TRACEABILITY_MANAGE");
  const [sending, setSending] = useState(false);
  return (
    <div className="space-y-6">
      <PageHeader
        title="Stock transfers"
        subtitle="Stock sent between plants. It leaves at once, and becomes a lot or batch of its own where it is received."
      >
        {canManage && (
          <Button onClick={() => setSending(true)}>Send stock</Button>
        )}
      </PageHeader>
      <DataTable
        path="/stock-transfers"
        columns={columns}
        emptyMessage="No stock has moved between plants."
      />
      {sending && <SendDialog onClose={() => setSending(false)} />}
    </div>
  );
}
