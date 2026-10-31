"use client";

import { useQuery } from "@tanstack/react-query";
import { useParams } from "next/navigation";
import { useState } from "react";
import { AttachmentPanel } from "@/components/records/attachment-panel";
import { Details, PageHeader, QueryState, RecordLink, Section, StatusBadge, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import { formatQuantity, type FieldDef } from "@/lib/record-form";
import type { Receipt } from "@/lib/types";

const VOID_FIELDS: FieldDef[] = [{ name: "reason", label: "Reason", required: true }];

export default function MaterialReceiptPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/material-receipts/${id}`;
  const canManage = usePermission("RECEIPT_MANAGE");
  const refresh = useRefresh();
  const [voiding, setVoiding] = useState(false);
  const { data: receipt, error } = useQuery({ queryKey: [path], queryFn: () => api.get<Receipt>(path) });
  if (!receipt) return <QueryState error={error} />;

  return (
    <div className="space-y-6">
      <PageHeader title={receipt.grnNumber} subtitle={<StatusBadge status={receipt.status} />}>
        {canManage && receipt.status === "RECEIVED" && (
          <Button variant="destructive" onClick={() => setVoiding(true)}>
            Void receipt
          </Button>
        )}
      </PageHeader>
      <Section title="Details">
        <Details
          items={[
            ["Supplier", receipt.supplierName],
            ["Purchase order", <RecordLink key="po" href={`/purchase-orders/${receipt.purchaseOrderId}`}>View order</RecordLink>],
            ["Received on", receipt.receivedOn],
            ["Delivery note", receipt.deliveryNote],
            ["Notes", receipt.notes],
            ...(receipt.voidReason ? ([["Void reason", receipt.voidReason]] as [string, string][]) : []),
          ]}
        />
      </Section>
      <Section title="Items">
        <div className="overflow-x-auto">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Material</TableHead>
                <TableHead>Supplier batch</TableHead>
                <TableHead>Internal lot</TableHead>
                <TableHead>Quantity</TableHead>
                <TableHead>Inspection</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {receipt.items.map((item) => (
                <TableRow key={item.id}>
                  <TableCell>
                    {item.materialCode} – {item.materialName}
                  </TableCell>
                  <TableCell>{item.supplierBatch}</TableCell>
                  <TableCell>{item.internalBatch}</TableCell>
                  <TableCell>{formatQuantity(item.quantity)}</TableCell>
                  <TableCell>
                    <StatusBadge status={item.inspectionStatus} />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </Section>
      <AttachmentPanel entityType="MATERIAL_RECEIPT" entityId={id} canManage={canManage} />
      {voiding && (
        <RecordDialog
          title="Void receipt"
          fields={VOID_FIELDS}
          onClose={() => setVoiding(false)}
          onSubmit={(values) => api.post(`${path}/void`, values).then(refresh)}
        />
      )}
    </div>
  );
}
