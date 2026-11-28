"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { PageHeader, useRefresh } from "@/components/records/parts";
import { RecordDialog } from "@/components/records/record-dialog";
import { Button } from "@/components/ui/button";
import { usePermission } from "@/hooks/use-me";
import { api } from "@/lib/api";
import type { FieldDef } from "@/lib/record-form";
import type { Unit } from "@/lib/types";
import { equivalence, unitRequest } from "@/lib/units";

export default function UnitsPage() {
  const canManage = usePermission("PRODUCT_MANAGE");
  const refresh = useRefresh();
  const { data: units = [] } = useQuery({ queryKey: ["/units"], queryFn: () => api.get<Unit[]>("/units") });
  const [adding, setAdding] = useState(false);
  const kinds = [...new Set(units.map((unit) => unit.dimension))].sort();
  const convertible = units.filter((unit) => unit.toBase !== null);
  const fields: FieldDef[] = [
    { name: "code", label: "Code, such as MG or KL", required: true },
    { name: "name", label: "Name", required: true },
    { name: "equalsQuantity", label: "One of this equals (amount)", type: "decimal" },
    {
      name: "equalsUnitId",
      label: "Of this unit",
      type: "select",
      options: convertible.map((unit) => ({ value: unit.id, label: `${unit.code} – ${unit.name}` })),
    },
  ];

  return (
    <div className="max-w-3xl space-y-6">
      <PageHeader
        title="Units of measure"
        subtitle="Quantities convert between units of the same kind, such as GM and KG. A unit stays fixed on a material or product once stock is recorded in it.">
        {canManage && <Button onClick={() => setAdding(true)}>Add unit</Button>}
      </PageHeader>
      {kinds.map((kind) => (
        <section key={kind} aria-labelledby={`kind-${kind}`} className="space-y-2">
          <h2 id={`kind-${kind}`} className="font-medium">
            {kind}
          </h2>
          <table className="w-full glass rounded-2xl text-sm">
            <thead>
              <tr className="text-left text-muted-foreground">
                <th className="p-2">Code</th>
                <th className="p-2">Name</th>
                <th className="p-2">Converts as</th>
                <th className="p-2">Kept by</th>
              </tr>
            </thead>
            <tbody>
              {units
                .filter((unit) => unit.dimension === kind)
                .sort((a, b) => Number(a.toBase ?? 0) - Number(b.toBase ?? 0))
                .map((unit) => (
                  <tr key={unit.id} className="border-t">
                    <td className="p-2 font-medium">{unit.code}</td>
                    <td className="p-2">{unit.name}</td>
                    <td className="p-2">{equivalence(unit, units)}</td>
                    <td className="p-2 text-muted-foreground">{unit.system ? "QualityOS" : "Your organisation"}</td>
                  </tr>
                ))}
            </tbody>
          </table>
        </section>
      ))}
      {adding && (
        <RecordDialog
          title="Add unit"
          fields={fields}
          extra={
            <p className="text-sm text-muted-foreground">
              Say what one of it equals, such as 1 MG = 0.001 GM, and it converts with every unit of that kind. Leave
              both empty for a reading that never converts, such as viscosity in cP.
            </p>
          }
          onClose={() => setAdding(false)}
          onSubmit={(values) => api.post("/units", unitRequest(values)).then(refresh)}
        />
      )}
    </div>
  );
}
