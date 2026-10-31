"use client";

import { useQuery } from "@tanstack/react-query";
import { useParams } from "next/navigation";
import { PurchaseOrderForm } from "@/components/records/purchase-order-form";
import { QueryState } from "@/components/records/parts";
import { api } from "@/lib/api";
import type { PurchaseOrder } from "@/lib/types";

export default function EditPurchaseOrderPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/purchase-orders/${id}`;
  const { data: order, error } = useQuery({ queryKey: [path], queryFn: () => api.get<PurchaseOrder>(path) });
  if (!order) return <QueryState error={error} />;
  return <PurchaseOrderForm order={order} />;
}
