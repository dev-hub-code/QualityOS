"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useParams, useRouter } from "next/navigation";
import { useState } from "react";
import { useFieldArray, useForm } from "react-hook-form";
import { applyServerErrors } from "@/components/forms/apply-server-errors";
import { Field, FormError } from "@/components/forms/field";
import { PageHeader, QueryState, Section } from "@/components/records/parts";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { api } from "@/lib/api";
import { formatQuantity } from "@/lib/record-form";
import { receiptSchema, type ReceiptValues } from "@/lib/schemas";
import type { PurchaseOrder, PurchaseOrderItem, Receipt } from "@/lib/types";

const SELECT_CLASS = "h-9 w-full rounded-md border border-input bg-transparent px-2 text-sm";

function remaining(item: PurchaseOrderItem) {
  return Number(item.orderedQuantity) - Number(item.receivedQuantity);
}

function ReceiptForm({ order }: { order: PurchaseOrder }) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [formError, setFormError] = useState("");
  const openItems = order.items.filter((item) => remaining(item) > 0);
  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<ReceiptValues>({
    resolver: zodResolver(receiptSchema),
    defaultValues: {
      receivedOn: new Date().toISOString().slice(0, 10),
      deliveryNote: "",
      items: openItems.map((item) => ({ purchaseOrderItemId: item.id, supplierBatch: "", quantity: "" })),
    },
  });
  const { fields, append, remove } = useFieldArray({ control, name: "items" });

  async function onSubmit(values: ReceiptValues) {
    setFormError("");
    try {
      const receipt = await api.post<Receipt>("/material-receipts", {
        ...values,
        purchaseOrderId: order.id,
        deliveryNote: values.deliveryNote || null,
      });
      await queryClient.invalidateQueries();
      router.push(`/material-receipts/${receipt.id}`);
    } catch (error) {
      setFormError(applyServerErrors(error, setError));
    }
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="space-y-6" noValidate>
      <FormError message={formError} />
      <Section title="Delivery">
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Received on" id="receivedOn" type="date" error={errors.receivedOn} {...register("receivedOn")} />
          <Field label="Delivery note" id="deliveryNote" error={errors.deliveryNote} {...register("deliveryNote")} />
        </div>
      </Section>
      <Section
        title="Items"
        action={
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => append({ purchaseOrderItemId: openItems[0].id, supplierBatch: "", quantity: "" })}
          >
            Add batch
          </Button>
        }
      >
        <div className="space-y-4">
          {fields.map((field, index) => (
            <div key={field.id} className="grid items-end gap-3 sm:grid-cols-[1fr_12rem_10rem_auto]">
              <div className="space-y-1.5">
                <Label htmlFor={`line-${index}`}>Order line</Label>
                <select
                  id={`line-${index}`}
                  className={SELECT_CLASS}
                  {...register(`items.${index}.purchaseOrderItemId`)}
                >
                  {openItems.map((item) => (
                    <option key={item.id} value={item.id}>
                      {item.materialCode} ({formatQuantity(String(remaining(item)))} remaining)
                    </option>
                  ))}
                </select>
              </div>
              <Field
                label="Supplier batch"
                id={`batch-${index}`}
                error={errors.items?.[index]?.supplierBatch}
                {...register(`items.${index}.supplierBatch`)}
              />
              <Field
                label="Quantity received"
                id={`received-${index}`}
                inputMode="decimal"
                error={errors.items?.[index]?.quantity}
                {...register(`items.${index}.quantity`)}
              />
              <Button type="button" variant="ghost" disabled={fields.length === 1} onClick={() => remove(index)}>
                Remove
              </Button>
            </div>
          ))}
        </div>
      </Section>
      <Button type="submit" disabled={isSubmitting}>
        Record receipt
      </Button>
    </form>
  );
}

export default function ReceiveMaterialPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/purchase-orders/${id}`;
  const { data: order, error } = useQuery({ queryKey: [path], queryFn: () => api.get<PurchaseOrder>(path) });
  if (!order) return <QueryState error={error} />;
  return (
    <div className="space-y-6">
      <PageHeader title="Receive material" subtitle={`${order.poNumber} · ${order.supplierName}`} />
      <ReceiptForm order={order} />
    </div>
  );
}
