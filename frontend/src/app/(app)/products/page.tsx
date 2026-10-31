"use client";

import { createColumnHelper } from "@tanstack/react-table";
import { useState } from "react";
import { DataTable } from "@/components/records/data-table";
import { PageHeader, RecordLink, useProductCategories, useRefresh, useUnits } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { productFields } from "@/lib/record-fields";
import type { Product } from "@/lib/types";

const column = createColumnHelper<Product>();
const columns = [
  column.accessor("code", {
    header: "Code",
    cell: (info) => <RecordLink href={`/products/${info.row.original.id}`}>{info.getValue()}</RecordLink>,
  }),
  column.accessor("name", { header: "Name" }),
  column.accessor("productType", {
    header: "Type",
    cell: (info) => (info.getValue() === "FINISHED" ? "Finished" : "Semi-finished"),
  }),
  column.accessor("currentRevision", { header: "Revision" }),
];

export default function ProductsPage() {
  const canManage = usePermission("PRODUCT_MANAGE");
  const units = useUnits();
  const categories = useProductCategories();
  const refresh = useRefresh();
  const [adding, setAdding] = useState(false);
  return (
    <div className="space-y-6">
      <PageHeader title="Products">
        {canManage && <Button onClick={() => setAdding(true)}>Add product</Button>}
      </PageHeader>
      <DataTable
        path="/products"
        columns={columns}
        searchLabel="Search products"
        emptyMessage="No products yet."
      />
      {adding && (
        <RecordDialog
          title="Add product"
          fields={productFields(units.options, categories)}
          onClose={() => setAdding(false)}
          onSubmit={(values) => api.post("/products", values).then(refresh)}
        />
      )}
    </div>
  );
}
