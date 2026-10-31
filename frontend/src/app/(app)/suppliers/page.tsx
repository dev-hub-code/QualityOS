"use client";

import { createColumnHelper } from "@tanstack/react-table";
import { useState } from "react";
import { DataTable } from "@/components/records/data-table";
import {
  PageHeader,
  RecordLink,
  StatusBadge,
  useRefresh,
  useSupplierCategories,
} from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { supplierFields } from "@/lib/record-fields";
import type { Supplier } from "@/lib/types";

const STATUSES = ["DRAFT", "PENDING_APPROVAL", "APPROVED", "REJECTED", "BLOCKED"];

const column = createColumnHelper<Supplier>();
const columns = [
  column.accessor("code", {
    header: "Code",
    cell: (info) => <RecordLink href={`/suppliers/${info.row.original.id}`}>{info.getValue()}</RecordLink>,
  }),
  column.accessor("name", { header: "Name" }),
  column.accessor("city", { header: "City" }),
  column.accessor("status", { header: "Status", cell: (info) => <StatusBadge status={info.getValue()} /> }),
];

export default function SuppliersPage() {
  const canCreate = usePermission("SUPPLIER_CREATE");
  const categories = useSupplierCategories();
  const refresh = useRefresh();
  const [adding, setAdding] = useState(false);
  const [status, setStatus] = useState("");
  return (
    <div className="space-y-6">
      <PageHeader title="Suppliers">
        {canCreate && <Button onClick={() => setAdding(true)}>Add supplier</Button>}
      </PageHeader>
      <select
        aria-label="Filter by status"
        className="h-9 rounded-md border border-input bg-transparent px-2 text-sm"
        value={status}
        onChange={(event) => setStatus(event.target.value)}
      >
        <option value="">All statuses</option>
        {STATUSES.map((value) => (
          <option key={value} value={value}>
            {value.replaceAll("_", " ").toLowerCase()}
          </option>
        ))}
      </select>
      <DataTable
        path="/suppliers"
        columns={columns}
        filter={status && `status=${status}`}
        searchLabel="Search suppliers"
        emptyMessage="No suppliers found."
      />
      {adding && (
        <RecordDialog
          title="Add supplier"
          fields={supplierFields(categories)}
          onClose={() => setAdding(false)}
          onSubmit={(values) => api.post("/suppliers", values).then(refresh)}
        />
      )}
    </div>
  );
}
