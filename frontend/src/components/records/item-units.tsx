"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import type { UseFormRegisterReturn } from "react-hook-form";
import { FormError } from "@/components/forms/field";
import { useAction, useUnits } from "@/components/records/parts";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { api } from "@/lib/api";
import { formatQuantity } from "@/lib/record-form";
import type { ItemUnits, Unit } from "@/lib/types";

const SELECT_CLASS =
  "h-9 w-full rounded-xl border border-input bg-white/70 px-2 text-sm";

export function useItemUnits(itemId: string | undefined) {
  return useQuery({
    queryKey: [`/items/${itemId}/units`],
    queryFn: () => api.get<ItemUnits>(`/items/${itemId}/units`),
    enabled: Boolean(itemId),
  });
}

/**
 * The unit a quantity of one material or product is entered in. Empty means the item's own unit; the others are the
 * units of its kind and those its conversions reach, such as DRUM for glycerin kept in KG.
 */
export function ItemUnitField({
  itemId,
  id,
  registration,
}: {
  itemId: string | undefined;
  id: string;
  registration: UseFormRegisterReturn;
}) {
  const units = useUnits();
  const { data } = useItemUnits(itemId);
  const others = (data?.enterableUnitIds ?? []).filter(
    (unit) => unit !== data?.stockUnitId,
  );
  return (
    <div className="space-y-1.5">
      <Label htmlFor={id}>Unit</Label>
      <select
        id={id}
        className={SELECT_CLASS}
        disabled={!data}
        {...registration}
      >
        <option value="">{data ? units.label(data.stockUnitId) : "–"}</option>
        {others.map((unit) => (
          <option key={unit} value={unit}>
            {units.label(unit)}
          </option>
        ))}
      </select>
    </div>
  );
}

/** "4 DRUM (800 KG)" for a quantity entered in another unit, or "800 KG". */
export function enteredLabel(
  quantity: string,
  unitCode: string,
  entered: { quantity: string | null; unitCode: string | null },
): string {
  const own = `${formatQuantity(quantity)} ${unitCode}`.trim();
  if (!entered.quantity || !entered.unitCode) return own;
  return `${formatQuantity(entered.quantity)} ${entered.unitCode} (${own})`;
}

/**
 * The units a conversion can be added for: not the item's own unit or one of its kind, not a unit already reached
 * through one of its conversions (ML once LTR has one), and not a reading such as °C, % or HRC, which QualityOS keeps
 * without a factor and which no stock is counted in.
 */
export function conversionChoices(
  units: Unit[],
  stock: Unit | undefined,
  ruled: (Unit | undefined)[],
): Unit[] {
  const sameKind = (one: Unit, other: Unit | undefined) =>
    other !== undefined &&
    one.toBase !== null &&
    other.toBase !== null &&
    one.dimension === other.dimension;
  return units.filter(
    (unit) =>
      unit.id !== stock?.id &&
      !sameKind(unit, stock) &&
      !ruled.some((rule) => rule?.id === unit.id || sameKind(unit, rule)) &&
      !(unit.system && unit.toBase === null),
  );
}

/** Why a typed factor cannot be saved, or "" when it can: a plain number above zero, such as 25 or 0.96. */
export function factorProblem(
  factor: string,
  unitCode: string,
  stockCode: string,
): string {
  const typed = factor.trim();
  if (/^\d+(\.\d+)?$/.test(typed) && Number(typed) > 0) return "";
  return `Enter how many ${stockCode} one ${unitCode} holds, as a number above zero such as 25 or 0.96.`;
}

/**
 * A material's or product's own conversions, such as 1 DRUM = 200 KG, which let its quantities be ordered, received
 * and used in those units. A conversion is not edited: one no longer needed is removed and another added.
 */
export function UnitConversions({
  itemId,
  canManage,
}: {
  itemId: string;
  canManage: boolean;
}) {
  const { data } = useItemUnits(itemId);
  const all = useQuery({
    queryKey: ["/units"],
    queryFn: () => api.get<Unit[]>("/units"),
  });
  const { run, message, clear } = useAction();
  const [uomId, setUomId] = useState("");
  const [factor, setFactor] = useState("");
  const [invalid, setInvalid] = useState("");
  if (!data) return null;
  const units = all.data ?? [];
  const stock = units.find((unit) => unit.id === data.stockUnitId);
  const ruled = data.conversions.map((rule) =>
    units.find((unit) => unit.id === rule.uomId),
  );
  const choices = conversionChoices(units, stock, ruled);

  return (
    <section aria-labelledby="conversions-heading" className="space-y-3">
      <h2 id="conversions-heading" className="font-medium">
        Unit conversions
      </h2>
      <p className="text-sm text-muted-foreground">
        For this item only, such as a drum of 200 {stock?.code ?? "KG"} or a
        litre that weighs 1.26 KG. Units of the same kind, such as GM and KG,
        convert without one.
      </p>
      <FormError message={invalid || message} />
      {data.conversions.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          No conversions of its own.
        </p>
      ) : (
        <ul className="divide-y glass rounded-2xl text-sm">
          {data.conversions.map((rule) => (
            <li
              key={rule.id}
              className="flex items-center justify-between gap-3 p-3"
            >
              <span>
                1 {rule.unitCode} = {formatQuantity(rule.factor)} {stock?.code}
              </span>
              {canManage && (
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() =>
                    run(() =>
                      api.delete(
                        `/items/${itemId}/unit-conversions/${rule.id}`,
                      ),
                    )
                  }
                >
                  Remove
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
      {canManage && (
        <form
          className="grid items-end gap-3 sm:grid-cols-[10rem_auto_10rem_auto_auto]"
          onSubmit={(event) => {
            event.preventDefault();
            const unitCode =
              units.find((unit) => unit.id === uomId)?.code ?? "";
            const problem = factorProblem(factor, unitCode, stock?.code ?? "");
            setInvalid(problem);
            if (problem) {
              clear();
              return;
            }
            run(
              () =>
                api.post(`/items/${itemId}/unit-conversions`, {
                  uomId,
                  factor,
                }),
              {
                onSuccess: () => {
                  setUomId("");
                  setFactor("");
                },
              },
            );
          }}
        >
          <div className="space-y-1.5">
            <Label htmlFor="conversion-unit">1 of unit</Label>
            <select
              id="conversion-unit"
              className={SELECT_CLASS}
              value={uomId}
              onChange={(event) => setUomId(event.target.value)}
            >
              <option value="">Select…</option>
              {choices.map((unit) => (
                <option key={unit.id} value={unit.id}>
                  {unit.code} – {unit.name}
                </option>
              ))}
            </select>
          </div>
          <span className="pb-2 text-sm">=</span>
          <div className="space-y-1.5">
            <Label htmlFor="conversion-factor">Holds</Label>
            <Input
              id="conversion-factor"
              inputMode="decimal"
              value={factor}
              onChange={(event) => setFactor(event.target.value)}
            />
          </div>
          <span className="pb-2 text-sm">{stock?.code}</span>
          <Button type="submit" variant="outline" disabled={!uomId || !factor}>
            Add conversion
          </Button>
        </form>
      )}
    </section>
  );
}
