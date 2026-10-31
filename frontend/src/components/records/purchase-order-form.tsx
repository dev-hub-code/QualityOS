"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useFieldArray, useForm } from "react-hook-form";
import { applyServerErrors } from "@/components/forms/apply-server-errors";
import { Field, FormError } from "@/components/forms/field";
import { PageHeader, Section, useApprovedSuppliers, useFacilities, useMaterials } from "@/components/records/parts";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { api } from "@/lib/api";
import { formatQuantity } from "@/lib/record-form";
import { purchaseOrderSchema, type PurchaseOrderValues } from "@/lib/schemas";
import type { PurchaseOrder } from "@/lib/types";

const SELECT_CLASS = "h-9 w-full rounded-md border border-input bg-transparent px-2 text-sm";

export function PurchaseOrderForm({ order }: { order?: PurchaseOrder }) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const suppliers = useApprovedSuppliers();
  const facilities = useFacilities();
  const materials = useMaterials();
  const [formError, setFormError] = useState("");
  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<PurchaseOrderValues>({
    resolver: zodResolver(purchaseOrderSchema),
    defaultValues: order
      ? {
          supplierId: order.supplierId,
          facilityId: order.facilityId,
          orderDate: order.orderDate,
          expectedDate: order.expectedDate ?? "",
          items: order.items.map((item) => ({
            materialId: item.materialId,
            quantity: formatQuantity(item.orderedQuantity),
            unitPrice: formatQuantity(item.unitPrice),
          })),
        }
      : {
          supplierId: "",
          facilityId: "",
          orderDate: new Date().toISOString().slice(0, 10),
          expectedDate: "",
          items: [{ materialId: "", quantity: "", unitPrice: "" }],
        },
  });
  const { fields, append, remove } = useFieldArray({ control, name: "items" });

  async function onSubmit(values: PurchaseOrderValues) {
    setFormError("");
    try {
      const payload = {
        ...values,
        expectedDate: values.expectedDate || null,
        items: values.items.map((item) => ({ ...item, unitPrice: item.unitPrice || null })),
      };
      const saved = order
        ? await api.put<PurchaseOrder>(`/purchase-orders/${order.id}`, { ...payload, version: order.version })
        : await api.post<PurchaseOrder>("/purchase-orders", payload);
      await queryClient.invalidateQueries();
      router.push(`/purchase-orders/${saved.id}`);
    } catch (error) {
      setFormError(applyServerErrors(error, setError));
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader title={order ? `Edit ${order.poNumber}` : "New purchase order"} />
      <form onSubmit={handleSubmit(onSubmit)} className="space-y-6" noValidate>
        <FormError message={formError} />
        <Section title="Order">
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="space-y-1.5">
              <Label htmlFor="supplierId">Supplier</Label>
              <select id="supplierId" className={SELECT_CLASS} {...register("supplierId")}>
                <option value="">Select an approved supplier…</option>
                {suppliers.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
              {errors.supplierId && <p className="text-sm text-destructive">{errors.supplierId.message}</p>}
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="facilityId">Deliver to</Label>
              <select id="facilityId" className={SELECT_CLASS} {...register("facilityId")}>
                <option value="">Select a facility…</option>
                {facilities.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
              {errors.facilityId && <p className="text-sm text-destructive">{errors.facilityId.message}</p>}
            </div>
            <Field label="Order date" id="orderDate" type="date" error={errors.orderDate} {...register("orderDate")} />
            <Field label="Expected date" id="expectedDate" type="date" {...register("expectedDate")} />
          </div>
        </Section>
        <Section
          title="Items"
          action={
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => append({ materialId: "", quantity: "", unitPrice: "" })}
            >
              Add item
            </Button>
          }
        >
          <div className="space-y-4">
            {fields.map((field, index) => (
              <div key={field.id} className="grid items-end gap-3 sm:grid-cols-[1fr_10rem_10rem_auto]">
                <div className="space-y-1.5">
                  <Label htmlFor={`material-${index}`}>Material</Label>
                  <select id={`material-${index}`} className={SELECT_CLASS} {...register(`items.${index}.materialId`)}>
                    <option value="">Select…</option>
                    {materials.map((option) => (
                      <option key={option.value} value={option.value}>
                        {option.label}
                      </option>
                    ))}
                  </select>
                  {errors.items?.[index]?.materialId && (
                    <p className="text-sm text-destructive">{errors.items[index].materialId.message}</p>
                  )}
                </div>
                <Field
                  label="Quantity"
                  id={`quantity-${index}`}
                  inputMode="decimal"
                  error={errors.items?.[index]?.quantity}
                  {...register(`items.${index}.quantity`)}
                />
                <Field
                  label="Unit price"
                  id={`price-${index}`}
                  inputMode="decimal"
                  error={errors.items?.[index]?.unitPrice}
                  {...register(`items.${index}.unitPrice`)}
                />
                <Button type="button" variant="ghost" disabled={fields.length === 1} onClick={() => remove(index)}>
                  Remove
                </Button>
              </div>
            ))}
          </div>
        </Section>
        <Button type="submit" disabled={isSubmitting}>
          Save draft
        </Button>
      </form>
    </div>
  );
}
