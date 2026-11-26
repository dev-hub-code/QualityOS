"use client";

import { useQuery } from "@tanstack/react-query";
import { createColumnHelper } from "@tanstack/react-table";
import { useParams } from "next/navigation";
import { DataTable } from "@/components/records/data-table";
import { Details, PageHeader, QueryState, RecordLink, Section } from "@/components/records/parts";
import { DispatchActions } from "@/components/traceability/dispatch-actions";
import { api } from "@/lib/api";
import { formatQuantity } from "@/lib/record-form";
import type { Customer, Dispatch } from "@/lib/types";
import { formatCalendarDay } from "@/lib/dates";

const column = createColumnHelper<Dispatch>();
const columns = [
  column.accessor("dispatchNumber", {
    header: "Number",
    cell: (info) => <RecordLink href={`/dispatches/${info.row.original.id}`}>{info.getValue()}</RecordLink>,
  }),
  column.accessor("dispatchedOn", { header: "Date", cell: (info) => formatCalendarDay(info.getValue()) }),
  column.accessor("lines", {
    header: "Shipped",
    enableSorting: false,
    cell: (info) =>
      info
        .getValue()
        .map((line) => `${line.batchNumber} × ${formatQuantity(line.quantity)}`)
        .join(", "),
  }),
  column.display({ id: "state", header: "", cell: (info) => <DispatchActions dispatch={info.row.original} /> }),
];

export default function CustomerPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/customers/${id}`;
  const { data: customer, error } = useQuery({ queryKey: [path], queryFn: () => api.get<Customer>(path) });
  if (!customer) return <QueryState error={error} />;
  return (
    <div className="space-y-6">
      <PageHeader title={customer.name} subtitle={customer.code} />
      <Section title="Details">
        <Details
          items={[
            ["GSTIN", customer.gstin],
            ["Contact person", customer.contactPerson],
            ["Email", customer.email],
            ["Phone", customer.phone],
            ["Address", customer.address],
          ]}
        />
      </Section>
      <Section title="Dispatches">
        <DataTable
          path="/dispatches"
          filter={`customerId=${id}`}
          columns={columns}
          searchLabel="Search this customer's dispatches"
          emptyMessage="Nothing shipped to this customer yet."
        />
      </Section>
    </div>
  );
}
