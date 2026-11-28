"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useParams, useRouter } from "next/navigation";
import { useState } from "react";
import { useFieldArray, useForm, useWatch } from "react-hook-form";
import { applyServerErrors } from "@/components/forms/apply-server-errors";
import { Field, FormError } from "@/components/forms/field";
import { ItemUnitField } from "@/components/records/item-units";
import { PageHeader, QueryState, Section, useUnits } from "@/components/records/parts";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { api } from "@/lib/api";
import { formatQuantity } from "@/lib/record-form";
import { receiptSchema, type ReceiptValues } from "@/lib/schemas";
import type { PurchaseOrder, PurchaseOrderItem, Receipt } from "@/lib/types";
import { today } from "@/lib/dates";
import { useMe } from "@/hooks/use-me";

const SELECT_CLASS = "h-9 w-full rounded-xl border border-input bg-white/70 px-2 text-sm";

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
      receivedOn: today(),
      deliveryNote: "",
      // A line ordered in drums is counted in drums, unless changed.
      items: openItems.map((item) => ({
        purchaseOrderItemId: item.id,
        supplierBatch: "",
        quantity: "",
        expiryDate: "",
        uomId: item.enteredUomId ?? "",
      })),
    },
  });
  const { fields, append, remove } = useFieldArray({ control, name: "items" });
  const lines = useWatch({ control, name: "items" });
  const units = useUnits();
  const materialOf = (itemId: string | undefined) => order.items.find((item) => item.id === itemId)?.materialId;

  async function onSubmit(values: ReceiptValues) {
    setFormError("");
    try {
      const receipt = await api.post<Receipt>("/material-receipts", {
        ...values,
        purchaseOrderId: order.id,
        deliveryNote: values.deliveryNote || null,
        // A blank expiry is left to the material's shelf life.
        items: values.items.map((item) => ({ ...item, expiryDate: item.expiryDate || null, uomId: item.uomId || null })),
      });
      await queryClient.invalidateQueries();
      router.push(`/material-receipts/${receipt.id}`);
    } catch (error) {
      setFormError(applyServerErrors(error, setError));
    }
  }

  return (
    <form
      // A message from the last attempt goes, even when this one stops in the browser.
      onSubmit={(event) => {
        setFormError("");
        return handleSubmit(onSubmit)(event);
      }} className="space-y-6" noValidate>
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
            onClick={() =>
              append({
                purchaseOrderItemId: openItems[0].id,
                supplierBatch: "",
                quantity: "",
                expiryDate: "",
                uomId: openItems[0].enteredUomId ?? "",
              })
            }
          >
            Add batch
          </Button>
        }
      >
        <div className="space-y-4">
          {fields.map((field, index) => (
            <div key={field.id} className="grid items-end gap-3 sm:grid-cols-[1fr_10rem_8rem_8rem_9rem_auto]">
              <div className="space-y-1.5">
                <Label htmlFor={`line-${index}`}>Order line</Label>
                <select
                  id={`line-${index}`}
                  className={SELECT_CLASS}
                  {...register(`items.${index}.purchaseOrderItemId`)}
                >
                  {openItems.map((item) => (
                    <option key={item.id} value={item.id}>
                      {item.materialCode} ({formatQuantity(String(remaining(item)))} {units.label(item.uomId)} remaining)
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
              <ItemUnitField
                itemId={materialOf(lines?.[index]?.purchaseOrderItemId)}
                id={`unit-${index}`}
                registration={register(`items.${index}.uomId`)}
              />
              <Field
                label="Expiry date"
                id={`expiry-${index}`}
                type="date"
                error={errors.items?.[index]?.expiryDate}
                {...register(`items.${index}.expiryDate`)}
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

// Receiving belongs to whoever holds RECEIPT_MANAGE. Others who open the address see why, not a form the server refuses.
export default function ReceiveMaterialPage() {
  const { id } = useParams<{ id: string }>();
  const path = `/purchase-orders/${id}`;
  const me = useMe();
  const canReceive = me.data?.permissions.includes("RECEIPT_MANAGE") ?? false;
  const { data: order, error } = useQuery({
    queryKey: [path],
    queryFn: () => api.get<PurchaseOrder>(path),
    enabled: canReceive,
  });
  if (!me.data) return <QueryState error={me.error} />;
  if (!canReceive) {
    return <p>You do not have permission to do this.</p>;
  }
  if (!order) return <QueryState error={error} />;
  return (
    <div className="space-y-6">
      <PageHeader title="Receive material" subtitle={`${order.poNumber} · ${order.supplierName}`} />
      <ReceiptForm order={order} />
    </div>
  );
}
