"use client";

import { useQuery } from "@tanstack/react-query";
import { useParams } from "next/navigation";
import { useState } from "react";
import { FormError } from "@/components/forms/field";
import { ContactTable } from "@/components/recalls/contact-table";
import { RecallDashboardTiles } from "@/components/recalls/recall-dashboard";
import { Details, PageHeader, QueryState, RecordLink, Section, StatusBadge, useAction, useAssignees, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { formatQuantity, type FieldDef } from "@/lib/record-form";
import type { RecallContact, RecallDashboard, RecallDetail, RecallHistoryEntry } from "@/lib/types";

const STATUSES = ["PENDING", "CONTACT_ATTEMPTED", "CONTACTED", "ACKNOWLEDGED", "ACTION_REQUIRED", "RETURN_IN_PROGRESS", "RESOLVED", "UNREACHABLE"];
const CLOSURE_FIELDS: FieldDef[] = [
  { name: "note", label: "Closure note", type: "textarea", required: true },
  { name: "acceptOutstanding", label: "I accept that goods remain with customers, as explained above", type: "checkbox" },
];
const SEND_BACK_FIELDS: FieldDef[] = [{ name: "reason", label: "What still needs doing", type: "textarea", required: true }];
const SELECT = "h-9 rounded-md border bg-background px-2 text-sm";

function Closure({ recall, path }: { recall: RecallDetail; path: string }) {
  const canRequest = usePermission("RECALL_MANAGE");
  const canClose = usePermission("RECALL_CLOSE");
  const refresh = useRefresh();
  const { run, message } = useAction();
  const [dialog, setDialog] = useState<"request" | "back" | null>(null);
  const { review } = recall;
  return (
    <Section title="Closure review">
      <FormError message={message} />
      <Details
        items={[
          ["Customers contacted", `${review.contacted} of ${review.customers}`],
          ["Customers acknowledged", `${review.acknowledged} of ${review.customers}`],
          ["Exceptions", review.exceptions],
          ["Dispatched", formatQuantity(review.dispatchedQuantity)],
          ["Returned", formatQuantity(review.returnedQuantity)],
          ["Still with customers", formatQuantity(review.outstandingQuantity)],
          ...(review.capaStatus ? [["Linked CAPA", review.capaStatus] as [string, string]] : []),
          ...(recall.closureRequestedBy ? [["Closure requested by", recall.closureRequestedBy] as [string, string]] : []),
          ...(recall.closureNote ? [["Closure note", recall.closureNote] as [string, string]] : []),
          ...(recall.closedBy ? [["Closed by", recall.closedBy] as [string, string]] : []),
        ]}
      />
      {review.unresolvedCustomers.length > 0 && (
        <p className="mt-2 text-sm">Unresolved: {review.unresolvedCustomers.join(", ")}</p>
      )}
      <div className="mt-3 flex flex-wrap gap-2">
        {canRequest && recall.status === "OPEN" && <Button onClick={() => setDialog("request")}>Request closure</Button>}
        {canClose && recall.status === "PENDING_CLOSURE" && (
          <>
            <Button onClick={() => run(() => api.post(`${path}/close`).then(refresh))}>Approve closure</Button>
            <Button variant="outline" onClick={() => setDialog("back")}>
              Send back
            </Button>
          </>
        )}
      </div>
      {dialog === "request" && (
        <RecordDialog
          title="Request closure"
          fields={CLOSURE_FIELDS}
          onClose={() => setDialog(null)}
          onSubmit={async (values) => {
            await api.post(`${path}/request-closure`, values);
            refresh();
          }}
        />
      )}
      {dialog === "back" && (
        <RecordDialog
          title="Send back"
          fields={SEND_BACK_FIELDS}
          onClose={() => setDialog(null)}
          onSubmit={async (values) => {
            await api.post(`${path}/send-back`, values);
            refresh();
          }}
        />
      )}
    </Section>
  );
}

export default function RecallPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/recalls/${id}`;
  const [status, setStatus] = useState("");
  const [assignedTo, setAssignedTo] = useState("");
  const [customer, setCustomer] = useState("");
  const assignees = useAssignees();
  const filter = new URLSearchParams(
    Object.entries({ status, assignedTo, customerId: customer }).filter(([, value]) => value),
  ).toString();
  const contactsPath = `${path}/customers?${filter}`;
  const { data: recall, error } = useQuery({ queryKey: [path], queryFn: () => api.get<RecallDetail>(path) });
  const { data: dashboard } = useQuery({
    queryKey: [path, "dashboard"],
    queryFn: () => api.get<RecallDashboard>(`${path}/dashboard`),
  });
  const { data: all = [] } = useQuery({
    queryKey: [path, "all-customers"],
    queryFn: () => api.get<RecallContact[]>(`${path}/customers`),
  });
  const { data: contacts = [] } = useQuery({
    queryKey: [path, contactsPath],
    queryFn: () => api.get<RecallContact[]>(contactsPath),
  });
  const { data: history = [] } = useQuery({
    queryKey: [path, "history"],
    queryFn: () => api.get<RecallHistoryEntry[]>(`${path}/contact-history`),
  });
  if (!recall) return <QueryState error={error} />;
  return (
    <div className="space-y-6">
      <PageHeader title={recall.recallNumber} subtitle={`Batches ${recall.batchNumbers.join(", ")}`}>
        <StatusBadge status={recall.status} />
        <RecordLink href={`${path}/report`}>Report</RecordLink>
      </PageHeader>
      <Section title="Recall">
        <Details
          items={[
            ["Date", recall.recallDate],
            ["Reason", recall.reason],
            ["What customers must do", recall.instructions],
          ]}
        />
      </Section>
      {dashboard && <RecallDashboardTiles dashboard={dashboard} />}
      <Section title="Customers">
        <div className="mb-3 flex flex-wrap gap-2">
          <select aria-label="Filter by status" className={SELECT} value={status} onChange={(event) => setStatus(event.target.value)}>
            <option value="">All statuses</option>
            {STATUSES.map((value) => (
              <option key={value} value={value}>
                {value.replaceAll("_", " ").toLowerCase()}
              </option>
            ))}
          </select>
          <select aria-label="Filter by customer" className={SELECT} value={customer} onChange={(event) => setCustomer(event.target.value)}>
            <option value="">All customers</option>
            {all.map((contact) => (
              <option key={contact.customerId} value={contact.customerId}>
                {contact.customerName}
              </option>
            ))}
          </select>
          <select aria-label="Filter by assignee" className={SELECT} value={assignedTo} onChange={(event) => setAssignedTo(event.target.value)}>
            <option value="">Anyone</option>
            {assignees.map((person) => (
              <option key={person.value} value={person.value}>
                {person.label}
              </option>
            ))}
          </select>
        </div>
        <ContactTable recallId={id} contacts={contacts} open={recall.status === "OPEN"} />
      </Section>
      <Closure recall={recall} path={path} />
      <Section title="Contact history">
        {history.length === 0 ? (
          <p className="text-sm text-muted-foreground">Nothing recorded yet.</p>
        ) : (
          <ul className="divide-y text-sm">
            {history.map((entry) => (
              <li key={entry.id} className="py-2">
                <p>
                  {entry.on} · {entry.customerName} · {entry.kindLabel}
                  {entry.outcomeLabel && ` · ${entry.outcomeLabel}`}
                </p>
                {entry.customerResponse && <p className="text-muted-foreground">{entry.customerResponse}</p>}
                {entry.notes && <p className="text-muted-foreground">{entry.notes}</p>}
                <p className="text-xs text-muted-foreground">Recorded by {entry.recordedBy}</p>
              </li>
            ))}
          </ul>
        )}
      </Section>
    </div>
  );
}
