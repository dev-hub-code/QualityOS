"use client";

import { useQuery } from "@tanstack/react-query";
import { useParams } from "next/navigation";
import { useState } from "react";
import { NcrStatusBadge } from "@/components/records/ncr-status-badge";
import { Details, PageHeader, QueryState, RecordLink, Section, StatusBadge, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { DispatchActions } from "@/components/traceability/dispatch-actions";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { formatQuantity, type FieldDef } from "@/lib/record-form";
import type { Dispatch, DispatchReturn } from "@/lib/types";
import { formatCalendarDay } from "@/lib/dates";

interface Complaint {
  id: string;
  ncrNumber: string;
  status: string;
}

/** A complaint about goods the customer kept, such as a phone call. It raises an NCR; returned goods are a return. */
function ComplaintDialog({ dispatch, onClose }: { dispatch: Dispatch; onClose: () => void }) {
  const refresh = useRefresh();
  const fields: FieldDef[] = [
    { name: "reference", label: "Complaint reference, such as a call or email", required: true },
    { name: "receivedOn", label: "Received on", type: "date", required: true },
    {
      name: "severity",
      label: "Severity",
      type: "select",
      required: true,
      options: [
        { value: "CRITICAL", label: "Critical" },
        { value: "MAJOR", label: "Major" },
        { value: "MINOR", label: "Minor" },
      ],
    },
    ...(dispatch.lines.length > 1
      ? [
          {
            name: "dispatchItemId",
            label: "Batch it is about (leave empty for the whole dispatch)",
            type: "select",
            options: dispatch.lines.map((line) => ({ value: line.id, label: `${line.batchNumber} – ${line.productCode}` })),
          } as FieldDef,
        ]
      : []),
    { name: "description", label: "What the customer reported", type: "textarea", required: true },
  ];
  return (
    <RecordDialog
      title={`Record complaint about ${dispatch.dispatchNumber}`}
      fields={fields}
      onClose={onClose}
      onSubmit={(values) =>
        api
          .post(`/dispatches/${dispatch.id}/complaints`, {
            ...values,
            dispatchItemId: values.dispatchItemId || (dispatch.lines.length === 1 ? dispatch.lines[0].id : null),
          })
          .then(refresh)
      }
    />
  );
}

export default function DispatchPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/dispatches/${id}`;
  const { data: dispatch, error } = useQuery({ queryKey: [path], queryFn: () => api.get<Dispatch>(path) });
  const { data: returns = [] } = useQuery({
    queryKey: [`${path}/reversals`],
    queryFn: () => api.get<DispatchReturn[]>(`${path}/reversals`),
  });
  const { data: complaints = [] } = useQuery({
    queryKey: [`${path}/complaints`],
    queryFn: () => api.get<Complaint[]>(`${path}/complaints`),
  });
  const canRaiseNcr = usePermission("NCR_CREATE");
  const [complaining, setComplaining] = useState(false);
  if (!dispatch) return <QueryState error={error} />;
  const canComplain = canRaiseNcr && dispatch.shippedOn !== null && !dispatch.reversalReason;
  return (
    <div className="space-y-6">
      <PageHeader title={dispatch.dispatchNumber} subtitle={`To ${dispatch.customerName}, dispatched ${formatCalendarDay(dispatch.dispatchedOn)}`}>
        <div className="flex flex-wrap items-center gap-2">
          <DispatchActions dispatch={dispatch} />
          {canComplain && (
            <Button variant="outline" size="sm" onClick={() => setComplaining(true)}>
              Record complaint
            </Button>
          )}
        </div>
      </PageHeader>
      <Section title="Details">
        <Details
          items={[
            ["Customer", <RecordLink key="customer" href={`/customers/${dispatch.customerId}`}>{dispatch.customerName}</RecordLink>],
            ["Dispatched on", dispatch.dispatchedOn],
            ["Reference", dispatch.reference],
            ["Shipped on", dispatch.shippedOn],
            ["Carrier or vehicle", dispatch.carrierReference],
            ["Reversed as a mistake", dispatch.reversalReason],
          ]}
        />
      </Section>
      <Section title="Shipped">
        <ul className="divide-y text-sm">
          {dispatch.lines.map((line) => (
            <li key={line.id} className="flex flex-wrap items-center justify-between gap-3 py-2">
              <span>
                <RecordLink href={`/traceability/${encodeURIComponent(line.batchNumber)}`}>{line.batchNumber}</RecordLink>{" "}
                {line.productCode} – {line.productName}
              </span>
              <span className="text-muted-foreground">
                {formatQuantity(line.quantity)}
                {Number(line.returnedQuantity) > 0 && `, ${formatQuantity(line.returnedQuantity)} returned`}
                {Number(line.expectedQuantity) > 0 && `, ${formatQuantity(line.expectedQuantity)} on its way back`}
              </span>
            </li>
          ))}
        </ul>
      </Section>
      <Section title="Returns">
        {returns.length === 0 ? (
          <p className="text-sm text-muted-foreground">Nothing has come back.</p>
        ) : (
          <ul className="divide-y text-sm">
            {returns.map((returned) => (
              <li key={returned.id} className="flex flex-wrap items-center justify-between gap-3 py-2">
                <span>
                  <RecordLink href={`/dispatch-reversals/${returned.id}`}>{returned.reversalNumber}</RecordLink> –{" "}
                  {returned.reason}
                </span>
                <StatusBadge status={returned.status} />
              </li>
            ))}
          </ul>
        )}
      </Section>
      <Section title="Complaints">
        {complaints.length === 0 ? (
          <p className="text-sm text-muted-foreground">No complaints about this dispatch.</p>
        ) : (
          <ul className="divide-y text-sm">
            {complaints.map((complaint) => (
              <li key={complaint.id} className="flex flex-wrap items-center justify-between gap-3 py-2">
                <RecordLink href={`/ncrs/${complaint.id}`}>{complaint.ncrNumber}</RecordLink>
                <NcrStatusBadge status={complaint.status} />
              </li>
            ))}
          </ul>
        )}
      </Section>
      {complaining && <ComplaintDialog dispatch={dispatch} onClose={() => setComplaining(false)} />}
    </div>
  );
}
